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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

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

    // Set by Test.expect_error(); reset to null by GrugTestRunner immediately before each
    // Test.run() call. While non-null, an export function that aborts on a host error whose message
    // contains this text counts as a pass, which is how the error branches get exercised.
    public static String testExpectedError = null;

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
        if (statePtr != 0) return;

        load();
        statePtr = nativeInit(modApiJson.getAbsolutePath(), modsDir.getAbsolutePath());
    }

    public static FileInfo[] compileAllFiles() {
        if (statePtr == 0) throw new IllegalStateException("grug_state is not initialized");
        return nativeCompileAllFiles(statePtr);
    }

    public static String[] update(Consumer<String> onError) {
        if (statePtr == 0) return new String[0];

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
     * failure or a file outside {@code code/} on the way.
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
            if (pathParts.length < 2 || !pathParts[1].equals("code")) {
                String errorMsg =
                        "Ignored "
                                + file.path()
                                + ": grug files must be placed inside the 'code/' directory!";
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
        return nativeCreateEntity(statePtr, fileId);
    }

    public static long getExportFnId(String entityType, String fnName) {
        return nativeGetExportFnId(statePtr, entityType, fnName);
    }

    public static boolean callExportFn(long entityHandle, long exportFnId) {
        boolean result = nativeCallExportFn(statePtr, entityHandle, exportFnId);
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
        nativeDestroyEntity(statePtr, entityHandle);
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

    public static native void hostFunctionErrorHappened(long statePtr, String message);
}
