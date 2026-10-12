package net.grug.minecraft.grug;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

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
            GrugNative.initGrugAdapter();
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
            statePtr =
                    GrugNative.nativeInit(modApiJson.getAbsolutePath(), modsDir.getAbsolutePath());
        } finally {
            GrugStateLock.unlock();
        }
    }

    public static FileInfo[] compileAllFiles() {
        GrugStateLock.lock();
        try {
            if (statePtr == 0) throw new IllegalStateException("grug_state is not initialized");
            return GrugNative.nativeCompileAllFiles(statePtr);
        } finally {
            GrugStateLock.unlock();
        }
    }

    public static long createEntity(long fileId) {
        GrugStateLock.lock();
        try {
            return GrugNative.nativeCreateEntity(statePtr, fileId);
        } finally {
            GrugStateLock.unlock();
        }
    }

    public static long getExportFnId(String entityType, String fnName) {
        GrugStateLock.lock();
        try {
            return GrugNative.nativeGetExportFnId(statePtr, entityType, fnName);
        } finally {
            GrugStateLock.unlock();
        }
    }

    public static boolean callExportFn(long entityHandle, long exportFnId) {
        boolean result;
        GrugStateLock.lock();
        try {
            result = GrugNative.nativeCallExportFn(statePtr, entityHandle, exportFnId);
        } finally {
            GrugStateLock.unlock();
        }
        GrugErrors.throwPendingFatal();
        return result;
    }

    /**
     * For invariants that must never be broken. Use as {@code throw Grug.fatal("...")}. This
     * crashes the game, even when thrown inside a game function that a script called.
     */
    public static RuntimeException fatal(String message) {
        return GrugErrors.fatal(message);
    }

    public static RuntimeException fatal(String message, Throwable cause) {
        return GrugErrors.fatal(message, cause);
    }

    public static void destroyEntity(long entityHandle) {
        GrugStateLock.lock();
        try {
            GrugNative.nativeDestroyEntity(statePtr, entityHandle);
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

    public static void hostFunctionErrorHappened(long statePtr, String message) {
        GrugStateLock.lock();
        try {
            GrugNative.nativeHostFunctionErrorHappened(statePtr, message);
        } finally {
            GrugStateLock.unlock();
        }
    }
}
