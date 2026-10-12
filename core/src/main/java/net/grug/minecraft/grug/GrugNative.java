package net.grug.minecraft.grug;

/**
 * The native entry points into the grug-rs backend, declared here so the JNI names in adapter.c
 * bind to this class. A rename of either side is a link break, so the C symbols in adapter.c and
 * the {@code nativeGetExportFnId} call and extern the emitted C of core/generate.py reference have
 * to move with a move or rename of this class.
 *
 * <p>Callers hand over {@link Grug#statePtr} themselves and enter with the state lock held: the
 * lock-taking wrappers in {@link Grug}, the generated {@code ExportFns} and {@link
 * GrugModsWatcher#update} all take it where they run, and the lock is reentrant for the entries
 * that nest (see {@link GrugStateLock}).
 */
public final class GrugNative {
    private GrugNative() {}

    public static native void initGrugAdapter();

    public static native long nativeInit(String modApiPath, String modsDirPath);

    public static native FileInfo[] nativeCompileAllFiles(long statePtr);

    public static native FileInfo[] nativeUpdate(long statePtr);

    public static native long nativeCreateEntity(long statePtr, long fileId);

    public static native long nativeGetExportFnId(long statePtr, String entityType, String fnName);

    public static native boolean nativeCallExportFn(
            long statePtr, long entityHandle, long exportFnId);

    public static native void nativeDestroyEntity(long statePtr, long entityHandle);

    public static native String[] nativeGetUpdatedResources(long statePtr);

    public static native void nativeHostFunctionErrorHappened(long statePtr, String message);
}
