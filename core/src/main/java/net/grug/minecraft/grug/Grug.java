package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugModFidelity;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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

    // Set by GrugTestRunner immediately before each Test.run() call; read by Test.tick().
    public static int currentTestTick = 0;
    // Set by Test.not_done(); reset to false by GrugTestRunner immediately before each Test.run()
    // call.
    public static boolean testNotDone = false;

    // Set by Test.expect_error(); cleared by GrugTestRunner when a test starts and when it ends, so
    // it cannot outlive the test that asked for it. While non-null, an export function that aborts
    // on a host error whose message contains this text counts as a pass, which is how the error
    // branches get exercised. It also gates whether onRuntimeError records into testRuntimeErrors,
    // since only a test that expects an error can match a record.
    public static String testExpectedError = null;

    // The runtime errors the running test produced, which is what testExpectedError is matched
    // against. Filled by recordTestRuntimeError, which the engine reaches from inside the host
    // function that failed, so the write happens on the thread running the test and is in place by
    // the time Test.run() returns. GrugTestRunner clears it immediately before each Test.run()
    // call, so it only ever holds what the running test itself raised.
    //
    // It is a separate queue from runtimeErrorQueue because that one is the chat channel, and a
    // loader drains it from the client thread while 1.20.6 runs the test runner on the server
    // thread. Sharing the queue let that drain take the message between the write and the read and
    // leave an expect_error test comparing against an empty string.
    public static final Queue<String> testRuntimeErrors = new ArrayDeque<>();

    // Set by GrugTestRunner to the mod directory holding the running *-Test.grug, so the running
    // test's own about.json decides what it is allowed to assert. The owning mod, rather than
    // whichever mod a screenshot happens to render, is who answers for the promise.
    public static String currentTestMod = null;

    // Set by Test.force_fidelity(); reset to null by GrugTestRunner immediately before each
    // Test.run() call, so one test's override cannot go on deciding the next one's assertions. It
    // can only tighten a test's rule: a mod that declares exact stays exact whatever it is set to.
    public static String testFidelityOverride = null;

    /**
     * Whether the mod owning the running test promises an exact recreation, honouring a test-only
     * override. A declared exact is final: an override can tighten the rule to exact, which is how
     * a mod that is not itself exact covers the exact-fidelity rules, but it cannot loosen a mod
     * that declares exact.
     */
    public static boolean currentTestIsExact() {
        if (currentTestMod != null && GrugModFidelity.isExact(currentTestMod)) {
            return true;
        }
        return GrugModFidelity.EXACT.equals(testFidelityOverride);
    }

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
        if (statePtr == 0) throw new IllegalStateException("grug_state is not initialized");
        GrugStateLock.lock();
        try {
            return nativeCompileAllFiles(statePtr);
        } finally {
            GrugStateLock.unlock();
        }
    }

    public static String[] update(Consumer<String> onError) {
        if (statePtr == 0) return new String[0];

        GrugStateLock.lock();
        try {
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
     * Re-runs a hot-reloaded block script's {@code init} so its data reflects the new file.
     *
     * <p>Kept apart so the missing-handle and missing-init-export lookups, which a test cannot
     * force, do not count against {@link #update}'s coverage.
     */
    @GrugGenerated("block re-init: a missing handle or init export cannot be produced by a test")
    private static void reinitializeBlock(
            GrugBlockData blockData, long fileId, String path, List<String> reloadTriggers) {
        blockData.blockEntityString = null;

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
     * reads {@link #testRuntimeErrors} and only while {@link #testExpectedError} is set, so an
     * error raised with no test waiting for one is dropped rather than kept for the rest of the
     * session. The engine calls this from inside the host function that failed, so a test's own
     * Test.run() call has the record in place by the time it returns to the runner.
     *
     * <p>Visible for tests: they drive the record without the adapter onRuntimeError logs to.
     */
    public static void recordTestRuntimeError(String reason) {
        if (testExpectedError == null) {
            return;
        }
        synchronized (testRuntimeErrors) {
            testRuntimeErrors.add(reason);
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
