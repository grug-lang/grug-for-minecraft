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
 * the call that made it and is remembered until the test runner fails the test whose call it was,
 * or the run itself when no call of the test's was running; and a fatal an invariant throws is held
 * per thread until the next return from a native call rethrows it.
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

    /** Guards {@link #pendingHostFunctionException}, which more than one game thread reaches. */
    private static final Object pendingHostFunctionExceptionLock = new Object();

    /**
     * The first host function exception the test runner has not dealt with yet, or null.
     *
     * <p>A call the test made itself aborts where it was made, so the runner fails that test by
     * name without this. A Java exception from a host function has no such call when a render pass,
     * the room build or a fixture the test placed raises it between two ticks, and leaving it to
     * the log let the run end green with no test named. The runner fails the run for it instead, on
     * its next tick. See {@code GrugTestRunner.tick} and #890.
     */
    private static String pendingHostFunctionException;

    /**
     * Reports a Java exception a host function threw, which the generated host wrapper in {@code
     * generate.py} calls after clearing it. grug-rs cannot unwind the exception through its Rust
     * frames, so the message is handed back as a host function error, which stops the script at the
     * call that made it; the runner then fails the test whose call it was instead of reporting PASS
     * and leaving the exception in the log with no test named (#890).
     *
     * <p>Also called when the adapter itself fails to pass an argument to the function, with {@code
     * convertingArguments} set, which is the adapter's defect rather than grug's.
     */
    public static void onHostFunctionThrew(
            Throwable thrown, String function, boolean convertingArguments) {
        String message = hostFunctionThrewMessage(thrown, function, convertingArguments);
        // Before the runtime error, so the runner has the record by its next tick even when the
        // call that threw was not the test's own. See pendingHostFunctionException.
        recordHostFunctionException(message);
        Grug.hostFunctionErrorHappened(Grug.statePtr, message);
    }

    /**
     * Remembers a host function exception until the runner has dealt with it, keeping only the
     * first: the later ones are usually the same defect repeating every frame.
     */
    public static void recordHostFunctionException(String message) {
        synchronized (pendingHostFunctionExceptionLock) {
            if (pendingHostFunctionException == null) {
                pendingHostFunctionException = message;
            }
        }
    }

    /** Takes the record {@link #recordHostFunctionException} left, or null when there is none. */
    public static String takeHostFunctionException() {
        synchronized (pendingHostFunctionExceptionLock) {
            String message = pendingHostFunctionException;
            pendingHostFunctionException = null;
            return message;
        }
    }

    /**
     * The message a Java exception a host function threw is reported with.
     *
     * <p>The answer names the host function the bridge called, which is the last frame of the
     * exception's Java stack, because a call from native code starts that stack there; the frames
     * above it are the function's own helpers. The stack stays in the message: the JNI layer used
     * to print it, and it says which line of the host function is at fault.
     *
     * <p>{@code convertingArguments} says the exception came from passing an argument to the
     * function, so the message blames the adapter and names {@code function}, which is the
     * grug-facing name the wrapper called it by.
     *
     * <p>Total: the wrapper has no second channel for a report that failed, so this neither throws
     * nor answers null, and it names the function even when the JVM dropped the stack.
     */
    static String hostFunctionThrewMessage(
            Throwable thrown, String function, boolean convertingArguments) {
        try {
            StringWriter printed = new StringWriter();
            thrown.printStackTrace(new PrintWriter(printed));
            StackTraceElement[] stack = thrown.getStackTrace();
            StackTraceElement called = stack.length == 0 ? null : stack[stack.length - 1];
            String what;
            String defect;
            if (convertingArguments) {
                what = "The adapter failed to pass an argument to the host function " + function;
                defect = "the adapter";
            } else if (called != null) {
                what =
                        called.getClassName()
                                + "."
                                + called.getMethodName()
                                + " threw a Java exception";
                defect = "grug";
            } else {
                // HotSpot answers a shared, stackless instance for an implicit exception it has
                // thrown often (OmitStackTraceInFastThrow), which is exactly a host function
                // reading a stale id every tick. The wrapper's name for it is then the only one.
                what = "The host function " + function + " threw a Java exception";
                defect = "grug";
            }
            return what
                    + ", which is a defect in "
                    + defect
                    + " rather than in the script that called it"
                    + (stack.length == 0 && !convertingArguments
                            ? ", and the JVM dropped its stack because it throws this one"
                                    + " repeatedly, so there are no frames and no line to name"
                            : "")
                    + ":\n"
                    + printed.toString().trim();
        } catch (Throwable failed) {
            // Reading or printing the exception failed, which a Throwable can cause by overriding
            // those methods. The wrapper gets a message that still names the function it called.
            return "The host function "
                    + function
                    + " threw a Java exception, and formatting the report failed too ("
                    + failed.getClass().getName()
                    + "), which is a defect in grug rather than in the script that called it.";
        }
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
