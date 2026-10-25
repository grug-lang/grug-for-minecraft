package net.grug.minecraft.grug;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * The native side of the bridge: loading the adapter library, and the native entry points into the
 * grug-rs backend it exports, declared here so the JNI names in adapter.c bind to this class. A
 * rename of either side is a link break, so the C symbols in adapter.c and the {@code
 * nativeGetExportFnId} call and extern the emitted C of core/generate.py reference have to move
 * with a move or rename of this class.
 *
 * <p>Callers hand over {@link Grug#statePtr} themselves and enter with the state lock held: the
 * lock-taking wrappers in {@link Grug}, the generated {@code ExportFns} and {@link
 * GrugModsWatcher#update} all take it where they run, and the lock is reentrant for the entries
 * that nest (see {@link GrugStateLock}). That is why everything here is package-private: every
 * caller that takes the lock lives in this package, so nothing outside it can enter the state
 * without the lock.
 */
final class GrugNative {
    private static boolean loaded = false;

    private GrugNative() {}

    /** Loads the native adapter library and initializes it, at most once per process. */
    static synchronized void load() {
        loaded = nativeLibraryLoaded();
    }

    /** The natives the jar holds, as the file name each platform loads. */
    private static final String LINUX_NATIVE = "libadapter.so";

    private static final String WINDOWS_NATIVE = "adapter.dll";

    /**
     * Names the native the given platform loads, which is also its name under {@code /natives/} in
     * the jar. Only x86_64 natives are built, so any other architecture, like any other OS, is a
     * fatal that names the platform and what the jar does hold.
     */
    static String nativeFileName(String osName, String osArch) {
        boolean x86_64 = osArch.equals("amd64") || osArch.equals("x86_64");
        if (x86_64 && osName.startsWith("Windows")) return WINDOWS_NATIVE;
        if (x86_64 && osName.equals("Linux")) return LINUX_NATIVE;
        throw Grug.fatal(
                "grug has no native for "
                        + osName
                        + " on "
                        + osArch
                        + ", only "
                        + LINUX_NATIVE
                        + " (Linux x86_64) and "
                        + WINDOWS_NATIVE
                        + " (Windows x86_64)");
    }

    /**
     * Extracts and loads the native adapter, at most once.
     *
     * <p>Kept apart so the IO failure paths do not count against {@link #load}'s coverage.
     */
    @GrugGenerated("native library extraction: IO failures a healthy run cannot enter")
    private static synchronized boolean nativeLibraryLoaded() {
        if (loaded) return true;
        String name = nativeFileName(System.getProperty("os.name"), System.getProperty("os.arch"));
        try {
            int dot = name.lastIndexOf('.');
            File tempFile = File.createTempFile(name.substring(0, dot), name.substring(dot));
            tempFile.deleteOnExit();
            try (InputStream in = GrugNative.class.getResourceAsStream("/natives/" + name);
                    OutputStream out = Files.newOutputStream(tempFile.toPath())) {
                if (in == null) {
                    throw new IOException(name + " not found on the classpath");
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
            throw new RuntimeException("Failed to load " + name, e);
        }
        return true;
    }

    static native void initGrugAdapter();

    static native long nativeInit(String modApiPath, String modsDirPath);

    static native FileInfo[] nativeCompileAllFiles(long statePtr);

    static native FileInfo[] nativeUpdate(long statePtr);

    static native long nativeCreateEntity(long statePtr, long fileId);

    static native long nativeGetExportFnId(long statePtr, String entityType, String fnName);

    static native boolean nativeCallExportFn(long statePtr, long entityHandle, long exportFnId);

    static native boolean nativeCallExportFnWithId(
            long statePtr, long entityHandle, long exportFnId, long firstArgId);

    static native void nativeDestroyEntity(long statePtr, long entityHandle);

    static native String[] nativeGetUpdatedResources(long statePtr);

    static native void nativeHostFunctionErrorHappened(long statePtr, String message);
}
