package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pins that {@link GrugModsWatcher} counts each change the engine reports exactly once.
 *
 * <p>A Java test and not a grug one, because the engine can report one write more than once: its
 * Windows watcher forwards every notification {@code ReadDirectoryChangesW} produces, and its Linux
 * poller reports a file again whenever its mtime has moved since the previous scan. It only
 * deduplicates paths inside the batch one {@code update} drains. A grug test can therefore not
 * assert that one write raises {@code Test.hot_reload_count} by one, since a count of two could be
 * the engine's. Here the report is handed in, so a count of two can only be this class's.
 */
class GrugModsWatcherTest {
    private static final String PREFIX = "watcher_test/";

    private Object savedAdapter;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void installAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> null));
    }

    @AfterEach
    void restoreAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
    }

    /**
     * Removes what the fake reports left in the watcher's static state, so no later test in this
     * JVM sees a file ID, a count or a pending reload for a path that does not exist.
     */
    @AfterEach
    @SuppressWarnings("unchecked")
    void forgetTheFakeReports() throws Exception {
        GrugModsWatcher.fileIds.keySet().removeIf(path -> path.startsWith(PREFIX));

        Field counts = GrugModsWatcher.class.getDeclaredField("reportedChangeCounts");
        counts.setAccessible(true);
        ((Map<String, Integer>) counts.get(null))
                .keySet()
                .removeIf(path -> path.startsWith(PREFIX));

        // Only this test's resource report adds a pending reload path, since no fake file names a
        // block, and nothing else in a unit test run reports one.
        GrugModsWatcher.takePendingReloadPaths();
    }

    private static FileInfo file(String path, long fileId) {
        return new FileInfo(path, "a-Item.grug", "m", "Item", "a", fileId, "boom");
    }

    @Test
    void aFileThatCompiledIsCountedOnce() {
        String path = "watcher_test/code/compiled-Item.grug";
        GrugModsWatcher.applyReport(new FileInfo[] {file(path, 7)}, new String[0], errors::add);

        assertEquals(1, GrugModsWatcher.reportedChangeCount(path));
        assertEquals(0, errors.size());
    }

    @Test
    void aFileThatFailedToCompileIsCountedOnce() {
        String path = "watcher_test/code/broken-Item.grug";
        GrugModsWatcher.applyReport(
                new FileInfo[] {file(path, Grug.INVALID_GRUG_FILE_ID)}, new String[0], errors::add);

        assertEquals(1, GrugModsWatcher.reportedChangeCount(path));
        assertEquals(1, errors.size());
    }

    @Test
    void eachReportRaisesTheCountByOne() {
        String path = "watcher_test/code/twice-Item.grug";
        GrugModsWatcher.applyReport(new FileInfo[] {file(path, 8)}, new String[0], errors::add);
        GrugModsWatcher.applyReport(new FileInfo[] {file(path, 8)}, new String[0], errors::add);

        assertEquals(2, GrugModsWatcher.reportedChangeCount(path));
    }

    @Test
    void aResourceIsCountedOnce() {
        String path = "watcher_test/assets/texture.png";
        String[] triggers =
                GrugModsWatcher.applyReport(new FileInfo[0], new String[] {path}, errors::add);

        assertEquals(1, GrugModsWatcher.reportedChangeCount(path));
        assertArrayEquals(new String[] {path}, triggers);
    }

    /**
     * A data file is read by the server's own data reload, which a loader never asks for on a hot
     * reload, so a change under data/ is counted and reloads nothing. See #758.
     */
    @Test
    void aDataFileIsCountedButReloadsNothing() {
        String path = "watcher_test/data/recipe.json";
        String[] triggers =
                GrugModsWatcher.applyReport(new FileInfo[0], new String[] {path}, errors::add);

        assertEquals(1, GrugModsWatcher.reportedChangeCount(path));
        assertArrayEquals(new String[0], triggers);
    }

    /**
     * Only the mod's own assets tree is a client resource. A directory named assets deeper in the
     * path is a data pack's own file, which no client resource reload reads either. See #758.
     */
    @Test
    void aDeeperAssetsDirectoryIsNotAClientResource() {
        String path = "watcher_test/data/grug/assets/recipe.json";
        String[] triggers =
                GrugModsWatcher.applyReport(new FileInfo[0], new String[] {path}, errors::add);

        assertEquals(1, GrugModsWatcher.reportedChangeCount(path));
        assertArrayEquals(new String[0], triggers);
    }

    /**
     * An update-goldens run writes a golden into the mods tree while its tests run, and a reload of
     * the game's resources for it stalled 1.20.6's test until it timed out.
     */
    @Test
    void aScreenshotReferenceIsCountedButReloadsNothing() {
        String directory = "watcher_test/screenshots/engine";
        String golden = directory + "/1.20.6-forge.png";
        String[] triggers =
                GrugModsWatcher.applyReport(
                        new FileInfo[0], new String[] {directory, golden}, errors::add);

        assertEquals(1, GrugModsWatcher.reportedChangeCount(golden));
        assertArrayEquals(new String[0], triggers);
    }
}
