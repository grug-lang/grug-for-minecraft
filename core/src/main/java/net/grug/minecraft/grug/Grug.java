package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

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

    public static final WeakGrugValueMap entityData = new WeakGrugValueMap();
    private static final Map<GrugEntityType, Integer> nextEntityIndices = new HashMap<>();
    public static final Map<String, Long> fileIds = new HashMap<>();

    public static Object currentlyInitializingBlockEntity = null;
    public static GrugBlockData currentlyInitializingBlock = null;

    public static final Map<String, GrugBlockData> declaredBlocks = new HashMap<>();
    public static final Map<Long, GrugBlockData> blockDataByFileId = new HashMap<>();

    public static final Map<String, GrugItemData> declaredItems = new HashMap<>();
    public static final Map<Long, GrugItemData> itemDataByFileId = new HashMap<>();

    /**
     * The default strong root for host entities. A block-entity member scope points {@link
     * #fnEntities} at its own owner list for the duration of a creation or tick, but everything
     * created outside that window (including exports like {@code output_taken} and {@code
     * item_inserted}, which do not swap the list) is retained here. It is not only member scope, so
     * it is not named for members; once the rooting becomes explicit this may become {@code
     * memberFnEntities}. See #89 and #93.
     */
    public static final List<GrugObject> retainedFnEntities = new ArrayList<>();

    public static List<GrugObject> fnEntities = retainedFnEntities;

    public static final Map<String, Long> entityFileIdsByName = new HashMap<>();

    public static final Queue<String> runtimeErrorQueue = new ArrayDeque<>();
    public static final Queue<String> printQueue = new ArrayDeque<>();

    static {
        for (GrugEntityType type : GrugEntityType.values()) {
            nextEntityIndices.put(type, 0);
        }
    }

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

    public static String[] update(Consumer<String> onError) {
        GrugStateLock.lock();
        try {
            if (statePtr == 0) return new String[0];
            return updateLocked(onError);
        } finally {
            GrugStateLock.unlock();
        }
    }

    /** The body of {@link #update}, which runs while this thread holds the state lock. */
    private static String[] updateLocked(Consumer<String> onError) {
        FileInfo[] updatedFiles = nativeUpdate(statePtr);
        List<String> reloadTriggers = new ArrayList<>();

        // Count every reported change, including files that failed to compile. A test that waits
        // for
        // its own write to be noticed has to be able to observe the failure path too, and a broken
        // file never reaches validHotReloads' loop body.
        for (FileInfo file : updatedFiles) {
            noteReportedChange(file.path());
        }

        for (FileInfo file : validHotReloads(updatedFiles, onError)) {
            noteReportedChange(file.path());
            fileIds.put(file.path(), file.fileId());

            // Maintain the dynamic entity linking map
            if ("BlockEntity".equals(file.entityType())) {
                entityFileIdsByName.put(
                        GrugFileIndex.cleanEntityName(file.entityName()), file.fileId());
            }

            GrugCore.getAdapter()
                    .logInfo(
                            "Successfully hot-reloaded "
                                    + file.path()
                                    + " with file ID "
                                    + file.fileId());

            GrugBlockData blockData = blockDataByFileId.get(file.fileId());
            if (blockData != null) {
                reinitializeBlock(blockData, file.fileId(), file.path(), reloadTriggers);
            }
        }

        for (String resource : nativeGetUpdatedResources(statePtr)) {
            noteReportedChange(resource);
            reloadTriggers.add(resource);
        }

        // Every path this batch triggers a reload for, so the loader can take them as the batch
        // its reload answers.
        for (String trigger : reloadTriggers) {
            noteReloadTriggered(trigger);
        }

        return reloadTriggers.toArray(new String[0]);
    }

    /**
     * Per-path counts of the changes {@link #update} has reported, so a test can wait until the
     * engine has actually noticed a file it changed instead of assuming a write was observed.
     *
     * <p>Keyed by the path {@code update} reports, which is the same relative path a test passes to
     * the {@code Test} helpers.
     */
    private static final Map<String, Integer> reportedChangeCounts = new HashMap<>();

    private static void noteReportedChange(String path) {
        reportedChangeCounts.merge(path.replace('\\', '/'), 1, Integer::sum);
    }

    /** How many changes to {@code path} the engine has reported so far, or 0 if none. */
    public static int reportedChangeCount(String path) {
        return reportedChangeCounts.getOrDefault(path.replace('\\', '/'), 0);
    }

    /**
     * The paths {@link #update} has reported since the last take, and per-path counts of the
     * reloads loaders have finished.
     *
     * <p>A reload answers a batch of reported paths and reloads whatever they name, so finishing
     * one counts once for every path in the batch it was started for. The batch is taken when the
     * reload starts rather than when it finishes, because reloads overlap on 1.20.6: an older
     * reload's apply can land after a newer path was reported, and that path is only in the newer
     * reload. {@link #reportedChangeCount} says the engine saw a write; this says a loader has
     * answered it, which is what a test that checks a rendered frame has to wait for. The lock is
     * here rather than in the loaders because 1.20.6 reports changes from its server thread and
     * finishes reloads on its client thread.
     */
    private static final Object loaderReloadLock = new Object();

    private static final Set<String> pendingReloadPaths = new LinkedHashSet<>();
    private static final Map<String, Integer> loaderReloadCounts = new HashMap<>();

    private static void noteReloadTriggered(String path) {
        synchronized (loaderReloadLock) {
            pendingReloadPaths.add(path.replace('\\', '/'));
        }
    }

    /**
     * Takes the paths reported since the last take, for the reload a loader is about to start. The
     * loader credits exactly these when that reload finishes, so a path reported while a reload is
     * already in flight is answered by a later reload instead of being credited to the one in
     * flight.
     */
    public static String[] takePendingReloadPaths() {
        synchronized (loaderReloadLock) {
            String[] taken = pendingReloadPaths.toArray(new String[0]);
            pendingReloadPaths.clear();
            return taken;
        }
    }

    /** Records that a loader has finished the reload that answered {@code paths}. */
    public static void noteLoaderReloadFinished(String[] paths) {
        synchronized (loaderReloadLock) {
            for (String path : paths) {
                loaderReloadCounts.put(path, loaderReloadCounts.getOrDefault(path, 0) + 1);
            }
        }
    }

    /** How many loader reloads a change to {@code path} has finished, or 0 if none. */
    public static int loaderReloadCount(String path) {
        synchronized (loaderReloadLock) {
            return loaderReloadCounts.getOrDefault(path.replace('\\', '/'), 0);
        }
    }

    /**
     * Re-runs a hot-reloaded block script's {@code init} so its data reflects the new file.
     *
     * <p>Kept apart so the missing-handle and missing-init-export lookups, which a test cannot
     * force, do not count against {@link #update}'s coverage.
     */
    @GrugGenerated("block re-init: a missing handle or init export cannot be produced by a test")
    private static void reinitializeBlock(
            GrugBlockData blockData, long fileId, String path, List<String> reloadTriggers) {
        // Cleared so the reload's init() re-declares from scratch rather than keeping what the
        // previous version of the file declared. blockEntityString because a script that dropped
        // its set_block_entity would otherwise keep the old one, and customRender for the same
        // reason: a script that dropped its set_custom_render would otherwise go on drawing custom
        // geometry against whatever the new file exports.
        blockData.blockEntityString = null;
        blockData.customRender = false;

        currentlyInitializingBlock = blockData;
        long tempEntityHandle = createEntity(fileId);
        long initFnId = getExportFnId("Block", "init");

        if (tempEntityHandle != 0 && initFnId != INVALID_GRUG_EXPORT_FN_ID) {
            callExportFn(tempEntityHandle, initFnId);
        }

        if (tempEntityHandle != 0) {
            destroyEntity(tempEntityHandle);
        }
        currentlyInitializingBlock = null;

        reloadTriggers.add(path);
    }

    /**
     * Filters a hot-reload batch down to the files that can be reloaded, reporting a compile
     * failure or a file outside {@code code/} or {@code tests/} on the way.
     *
     * <p>Kept apart so those reporting paths do not count against {@link #update}'s coverage.
     */
    @GrugGenerated("hot-reload validation: compile failures and misplaced files are reported")
    private static List<FileInfo> validHotReloads(FileInfo[] files, Consumer<String> onError) {
        List<FileInfo> valid = new ArrayList<>();
        for (FileInfo file : files) {
            if (file.fileId() == INVALID_GRUG_FILE_ID) {
                String errorMsg =
                        "Failed to hot-reload " + file.fileName() + ":\n" + file.errorString();
                GrugCore.getAdapter().logError(errorMsg);
                if (onError != null) {
                    onError.accept(errorMsg);
                }
                continue;
            }

            String[] pathParts = file.path().replace('\\', '/').split("/");
            if (pathParts.length < 2 || !GrugFileIndex.holdsScripts(pathParts[1])) {
                String errorMsg =
                        "Ignored "
                                + file.path()
                                + ": grug files must be placed inside the 'code/' or 'tests/'"
                                + " directory!";
                GrugCore.getAdapter().logError(errorMsg);
                if (onError != null) {
                    onError.accept(errorMsg);
                }
                continue;
            }

            valid.add(file);
        }
        return valid;
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

    public static long addEntity(GrugEntityType type, Object object) {
        GrugObject grugObject = new GrugObject(type, object);
        int index = nextEntityIndices.get(type);
        nextEntityIndices.put(type, index + 1);
        long id = ((long) type.ordinal() << 32) | (index & 0xFFFFFFFFL);

        entityData.put(id, grugObject);
        fnEntities.add(grugObject);
        return id;
    }

    public static void addEntityWithId(long id, GrugEntityType type, Object object) {
        GrugObject grugObject = new GrugObject(type, object);
        entityData.put(id, grugObject);
        fnEntities.add(grugObject);
    }

    public static long createEntity(long fileId) {
        GrugStateLock.lock();
        try {
            return nativeCreateEntity(statePtr, fileId);
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * {@link #createEntity} with the host entities the call creates rooted in {@code owner} and
     * {@code me} resolving to {@code initializingBlockEntity}, under the state lock.
     *
     * <p>The root list and the {@code me} binding are process-global state that host functions read
     * while a call runs, so they have to be swapped inside the same critical section as the call. A
     * swap made around a call that takes the lock is a race with every other entry into grug: a
     * block-entity tick on the game thread swaps in a list it drops on return, and a chunk-compile
     * worker inside this call then roots the entities its member scope creates in that dropped
     * list. The script keeps their ids, the weak entity table drops them once the collector runs,
     * and the next host function that dereferences one throws inside the JNI call, which takes the
     * JVM down. Holding the lock across the swap, the call and the restore closes that window; the
     * lock is reentrant, so the native entry's own acquisition nests. See #258.
     */
    public static long createEntityWithOwner(
            long fileId, List<GrugObject> owner, Object initializingBlockEntity) {
        GrugStateLock.lock();
        try {
            List<GrugObject> oldFnEntities = fnEntities;
            Object oldInitializingBlockEntity = currentlyInitializingBlockEntity;
            fnEntities = owner;
            currentlyInitializingBlockEntity = initializingBlockEntity;
            try {
                return nativeCreateEntity(statePtr, fileId);
            } finally {
                currentlyInitializingBlockEntity = oldInitializingBlockEntity;
                fnEntities = oldFnEntities;
            }
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

    /**
     * {@link #callExportFn} with the host entities the call creates rooted in {@code owner} and
     * {@code me} resolving to {@code initializingBlockEntity}. The swap has the same hazard and the
     * same fix as {@link #createEntityWithOwner}.
     */
    public static void callExportFnWithOwner(
            long entityHandle,
            long exportFnId,
            List<GrugObject> owner,
            Object initializingBlockEntity) {
        GrugStateLock.lock();
        try {
            List<GrugObject> oldFnEntities = fnEntities;
            Object oldInitializingBlockEntity = currentlyInitializingBlockEntity;
            fnEntities = owner;
            currentlyInitializingBlockEntity = initializingBlockEntity;
            try {
                callExportFn(entityHandle, exportFnId);
            } finally {
                currentlyInitializingBlockEntity = oldInitializingBlockEntity;
                fnEntities = oldFnEntities;
            }
        } finally {
            GrugStateLock.unlock();
        }
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

    private static native FileInfo[] nativeUpdate(long statePtr);

    private static native long nativeCreateEntity(long statePtr, long fileId);

    private static native long nativeGetExportFnId(long statePtr, String entityType, String fnName);

    private static native boolean nativeCallExportFn(
            long statePtr, long entityHandle, long exportFnId);

    private static native void nativeDestroyEntity(long statePtr, long entityHandle);

    private static native String[] nativeGetUpdatedResources(long statePtr);

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
