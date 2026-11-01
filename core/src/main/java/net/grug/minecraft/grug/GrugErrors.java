package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * What grug reports back to the player, and the channels it goes through: a runtime error goes to
 * the chat queue a loader drains and, while a test expects one, to that test's record; a script's
 * print output and the test runner's progress lines go to the print queue, which a loader drains
 * into chat as well; a Java exception a host function threw becomes a runtime error, which stops
 * the call that made it; and a fatal an invariant throws is held per thread until the next return
 * from a native call rethrows it.
 *
 * <p>The engine reports a runtime error through {@link #onRuntimeError}, and a host function
 * wrapper hands a Java exception to {@link #onHostFunctionThrew}. The native adapter in adapter.c
 * resolves both by this class's fully qualified name, so a rename or move of this class has to take
 * that lookup with it.
 */
public final class GrugErrors {
    private GrugErrors() {}

    public static final Queue<String> runtimeErrorQueue = new ArrayDeque<>();
    public static final Queue<String> printQueue = new ArrayDeque<>();

    public static void onRuntimeError(String reason) {
        GrugCore.getAdapter().logError(reason);
        synchronized (runtimeErrorQueue) {
            runtimeErrorQueue.add(reason);
        }
        recordTestRuntimeError(reason);
    }

    /**
     * Records a runtime error for the expecting test, which is who matches it. Only the runner
     * reads {@link GrugTestState#testRuntimeErrors} and only while {@link
     * GrugTestState#testExpectedError} is set, so an error raised with no test waiting for one is
     * dropped rather than kept for the rest of the session. The engine calls this from inside the
     * host function that failed, so a test's own Test.tick() call has the record in place by the
     * time it returns to the runner.
     *
     * <p>Visible for tests: they drive the record without the adapter onRuntimeError logs to.
     */
    public static void recordTestRuntimeError(String reason) {
        GrugTestState.runtimeErrorCount++;
        if (GrugTestState.testExpectedError == null) {
            return;
        }
        synchronized (GrugTestState.testRuntimeErrors) {
            GrugTestState.testRuntimeErrors.add(reason);
        }
    }

    // A Java exception thrown inside a game function cannot unwind through grug-rs's Rust frames.
    // So the generated JNI wrapper clears it and hands it here, and onHostFunctionThrew turns it
    // into a host function error, which stops that script call and fails the test that made it
    // (#890). An invariant is not a script error, so fatal() also remembers the first one per
    // thread, and it is rethrown as soon as the native call returns to Java (see Grug.callExportFn
    // and the generated ExportFns), which is what crashes the game rather than containing it.
    private static final ThreadLocal<RuntimeException> pendingFatal = new ThreadLocal<>();

    /**
     * The message a Java exception that a host function threw is reported with, which the generated
     * host wrapper in {@code generate.py} hands to the grug state as a host function error. The
     * script then stops at the call that made it, so the runner fails the test whose call it was
     * instead of reporting PASS and leaving the exception in the log with no test named (#890).
     *
     * <p>The answer names the host function the bridge called, which is the last frame of the
     * exception's Java stack, because a call from native code starts that stack there. The stack
     * stays in the message: the JNI layer used to print it, and it says which line of the host
     * function is at fault.
     */
    public static String onHostFunctionThrew(Throwable thrown) {
        StackTraceElement[] stack = thrown.getStackTrace();
        StackTraceElement called = stack.length == 0 ? null : stack[stack.length - 1];
        StringWriter printed = new StringWriter();
        thrown.printStackTrace(new PrintWriter(printed));
        return (called == null
                        ? "A host function"
                        : called.getClassName() + "." + called.getMethodName())
                + " threw a Java exception, which is a defect in grug rather than in the script"
                + " that called it:\n"
                + printed.toString().trim();
    }

    /**
     * For invariants that must never be broken. Use as {@code throw Grug.fatal("...")}. This
     * crashes the game, even when thrown inside a game function that a script called.
     */
    public static RuntimeException fatal(String message) {
        return fatal(message, null);
    }

    public static RuntimeException fatal(String message, Throwable cause) {
        RuntimeException error =
                new IllegalStateException("Broken grug invariant: " + message, cause);
        if (pendingFatal.get() == null) {
            pendingFatal.set(error);
        }
        return error;
    }

    @GrugGenerated("invariant: a pending fatal is rethrown by callExportFn when one was set")
    public static void throwPendingFatal() {
        RuntimeException error = pendingFatal.get();
        if (error != null) {
            pendingFatal.remove();
            throw error;
        }
    }
}
