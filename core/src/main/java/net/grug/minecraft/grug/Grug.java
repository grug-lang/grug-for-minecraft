package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * The Java bridge to one native grug state.
 *
 * <p>grug's state is not thread safe by design, so every entry takes {@link GrugStateLock}. A call
 * that blocks on another thread which enters the state releases the lock while it waits; see {@link
 * #runWithStateLockReleased(Runnable)}.
 */
public final class Grug {
    private static boolean loaded = false;
    public static long statePtr = 0;
    public static final long INVALID_GRUG_FILE_ID = -1L;
    public static final long INVALID_GRUG_EXPORT_FN_ID = -1L;

    public static final Queue<String> runtimeErrorQueue = new ArrayDeque<>();
    public static final Queue<String> printQueue = new ArrayDeque<>();

    @GrugGenerated("utility class: never instantiated")
    private Grug() {}

    public static synchronized void load() {
        loaded = nativeLibraryLoaded();
    }

    /**
     * Extracts and loads the native adapter, at most once.
     *
     * <p>Kept apart so the IO failure paths do not count against {@link #load}'s coverage.
     */
    @GrugGenerated("native library extraction: IO failures a healthy run cannot enter")
    private static synchronized boolean nativeLibraryLoaded() {
        if (loaded) return true;
        try {
            File tempFile = File.createTempFile("libadapter", ".so");
            tempFile.deleteOnExit();
            try (InputStream in = Grug.class.getResourceAsStream("/natives/libadapter.so");
                    OutputStream out = Files.newOutputStream(tempFile.toPath())) {
                if (in == null) {
                    throw new IOException("libadapter.so not found on the classpath");
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            System.load(tempFile.getAbsolutePath());
            initGrugAdapter();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load libadapter.so", e);
        }
        return true;
    }

    public static void init(File modApiJson, File modsDir) {
        GrugStateLock.lock();
        try {
            if (statePtr != 0) return;

            load();
            statePtr = nativeInit(modApiJson.getAbsolutePath(), modsDir.getAbsolutePath());
        } finally {
            GrugStateLock.unlock();
        }
    }

    public static FileInfo[] compileAllFiles() {
        GrugStateLock.lock();
        try {
            if (statePtr == 0) throw new IllegalStateException("grug_state is not initialized");
            return nativeCompileAllFiles(statePtr);
        } finally {
            GrugStateLock.unlock();
        }
    }

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
     * host function that failed, so a test's own Test.run() call has the record in place by the
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

    public static long createEntity(long fileId) {
        GrugStateLock.lock();
        try {
            return nativeCreateEntity(statePtr, fileId);
        } finally {
            GrugStateLock.unlock();
        }
    }

    public static long getExportFnId(String entityType, String fnName) {
        GrugStateLock.lock();
        try {
            return nativeGetExportFnId(statePtr, entityType, fnName);
        } finally {
            GrugStateLock.unlock();
        }
    }

    public static boolean callExportFn(long entityHandle, long exportFnId) {
        boolean result;
        GrugStateLock.lock();
        try {
            result = nativeCallExportFn(statePtr, entityHandle, exportFnId);
        } finally {
            GrugStateLock.unlock();
        }
        throwPendingFatal();
        return result;
    }

    // The JNI layer prints and clears any exception thrown inside a game function, and the script
    // then carries on. So fatal() remembers the first error per thread, and it is rethrown as soon
    // as the native call returns to Java (see callExportFn and the generated ExportFns).
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

    public static void destroyEntity(long entityHandle) {
        GrugStateLock.lock();
        try {
            nativeDestroyEntity(statePtr, entityHandle);
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Runs a wait that blocks this thread on another thread's work with the state lock released,
     * then restores the lock. The blocked thread is not running grug code, so the other thread may
     * be the one active entry, which is how the client-thread marshalling in the loaders stays
     * within grug's one-active-thread contract.
     *
     * <p>The released task may run export functions, but it must not compile or update files. The
     * suspended call still holds the backend's file table borrow, so a nested compile would panic
     * against it instead of waiting for the outer call to return.
     */
    public static void runWithStateLockReleased(Runnable blockingWait) {
        GrugStateLock.runReleased(blockingWait);
    }

    private static native void initGrugAdapter();

    private static native long nativeInit(String modApiPath, String modsDirPath);

    private static native FileInfo[] nativeCompileAllFiles(long statePtr);

    // Public rather than wrapped because the only caller, GrugModsWatcher, enters with the state
    // lock already held, and there is no unlocked entry a wrapper could own.
    public static native FileInfo[] nativeUpdate(long statePtr);

    private static native long nativeCreateEntity(long statePtr, long fileId);

    private static native long nativeGetExportFnId(long statePtr, String entityType, String fnName);

    private static native boolean nativeCallExportFn(
            long statePtr, long entityHandle, long exportFnId);

    private static native void nativeDestroyEntity(long statePtr, long entityHandle);

    // Public for the same reason as nativeUpdate.
    public static native String[] nativeGetUpdatedResources(long statePtr);

    public static void hostFunctionErrorHappened(long statePtr, String message) {
        GrugStateLock.lock();
        try {
            nativeHostFunctionErrorHappened(statePtr, message);
        } finally {
            GrugStateLock.unlock();
        }
    }

    private static native void nativeHostFunctionErrorHappened(long statePtr, String message);
}
