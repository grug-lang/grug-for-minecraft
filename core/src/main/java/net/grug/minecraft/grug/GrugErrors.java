package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * What grug reports back to the player, and the channels it goes through: a runtime error goes to
 * the chat queue a loader drains and, while a test expects one, to that test's record; a script's
 * print output and the test runner's progress lines go to the print queue, which a loader drains
 * into chat as well; and a fatal an invariant throws is held per thread until the next return from
 * a native call rethrows it.
 *
 * <p>The engine reports a runtime error through {@link #onRuntimeError}, which the native adapter
 * in adapter.c resolves by this class's fully qualified name, so a rename or move of this class has
 * to take that lookup with it.
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
        if (GrugTestState.testExpectedError == null) {
            return;
        }
        synchronized (GrugTestState.testRuntimeErrors) {
            GrugTestState.testRuntimeErrors.add(reason);
        }
    }

    // The JNI layer prints and clears any exception thrown inside a game function, and the script
    // then carries on. So fatal() remembers the first error per thread, and it is rethrown as soon
    // as the native call returns to Java (see Grug.callExportFn and the generated ExportFns).
    private static final ThreadLocal<RuntimeException> pendingFatal = new ThreadLocal<>();

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
