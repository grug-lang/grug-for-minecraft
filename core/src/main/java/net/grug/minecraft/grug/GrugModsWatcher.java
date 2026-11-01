package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The poll of a mod's directory for changed files, the hot-reload it starts for the ones that can
 * be reloaded, and the two sides of a change a test can wait for: what the engine has reported, and
 * the reloads a loader has finished answering them.
 *
 * <p>{@link #update} runs while the state lock is held, and the reported-change and loader-reload
 * counts live here with it, because they exist so a test that changed a file can wait for the
 * engine to notice and the loader to answer instead of assuming either.
 */
public final class GrugModsWatcher {
    private GrugModsWatcher() {}

    public static final Map<String, Long> fileIds = new HashMap<>();

    public static String[] update(Consumer<String> onError) {
        GrugStateLock.lock();
        try {
            if (Grug.statePtr == 0) return new String[0];
            return updateLocked(onError);
        } finally {
            GrugStateLock.unlock();
        }
    }

    /** The body of {@link #update}, which runs while this thread holds the state lock. */
    private static String[] updateLocked(Consumer<String> onError) {
        FileInfo[] updatedFiles = GrugNative.nativeUpdate(Grug.statePtr);
        return applyReport(
                updatedFiles, GrugNative.nativeGetUpdatedResources(Grug.statePtr), onError);
    }

    /**
     * What {@link #update} does with one report from the engine: counts each reported path once,
     * reloads the files that compiled, and returns the client resources a loader has to reload for.
     *
     * <p>Package-private so a Java test can hand it a report and read the counts, because a grug
     * test cannot: it cannot tell a count the engine raised twice from one this class raised twice.
     */
    static String[] applyReport(
            FileInfo[] updatedFiles, String[] updatedResources, Consumer<String> onError) {
        List<String> reloadTriggers = new ArrayList<>();

        // Count every reported change, including files that failed to compile. A test that waits
        // for its own write to be noticed has to be able to observe the failure path too, and a
        // broken file never reaches validHotReloads' loop body.
        for (FileInfo file : updatedFiles) {
            noteReportedChange(file.path());
        }

        // The first loop has already counted these, so counting here would make a file that
        // compiled count twice per reported change and a file that failed once.
        for (FileInfo file : validHotReloads(updatedFiles, onError)) {
            fileIds.put(file.path(), file.fileId());

            // Maintain the dynamic entity linking map
            if ("BlockEntity".equals(file.entityType())) {
                GrugEntities.entityFileIdsByName.put(
                        GrugFileIndex.cleanEntityName(file.entityName()), file.fileId());
            }

            GrugCore.getAdapter()
                    .logInfo(
                            "Successfully hot-reloaded "
                                    + file.path()
                                    + " with file ID "
                                    + file.fileId());

            GrugBlockData blockData = GrugDeclarations.blockDataByFileId.get(file.fileId());
            if (blockData != null) {
                reinitializeBlock(blockData, file.fileId(), file.path(), reloadTriggers);
            }
        }

        for (String resource : updatedResources) {
            noteReportedChange(resource);
            if (!isScreenshotReference(resource) && isClientResource(resource)) {
                reloadTriggers.add(resource);
            }
        }

        // Every path this batch triggers a reload for, so the loader can take them as the batch
        // its reload answers.
        for (String trigger : reloadTriggers) {
            noteReloadTriggered(trigger);
        }

        return reloadTriggers.toArray(new String[0]);
    }

    /**
     * Whether {@code resource} is under a mod's {@code screenshots/} directory, which holds the
     * golden references a test's screenshots are compared with. The game never loads them, so a
     * change to one is not a reason to reload the game's resources. An update-goldens run writes
     * them while its tests are running, and on 1.20.6 the reload that followed stalled the test
     * that had just accepted one until it timed out.
     */
    private static boolean isScreenshotReference(String resource) {
        return resource.replace('\\', '/').matches("[^/]+/screenshots(/.*)?");
    }

    /**
     * Whether {@code resource} is under a mod's {@code assets/} directory, the only tree a client's
     * resource reload reads. A file under {@code data/} is read by the server's own data reload,
     * which a loader never asks for on a hot reload, so the client reloading every pack for one
     * buys nothing and holds the suite for seconds. See #758.
     */
    private static boolean isClientResource(String resource) {
        return resource.replace('\\', '/').matches("[^/]+/assets(/.*)?");
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
            // A reload finishes inside a game tick, and the game runs ticks back to back when it
            // is behind, so the next test call can come before a single frame has been drawn with
            // what the reload changed. Forgetting the settled frames makes world_settled false
            // until two frames have been drawn after this point, which a capture waits on. See
            // #324.
            GrugCore.getAdapter().forgetSettledFrames();
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
        blockData.flatLighting = false;
        blockData.hasShape = false;
        blockData.hasDisplayTick = false;

        GrugDeclarations.initializeBlock(blockData, fileId);

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
            if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
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
}
