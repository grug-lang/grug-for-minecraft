package net.grug.minecraft.grug;

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
        GrugModsWatcher.applyReport(new FileInfo[0], new String[] {path}, errors::add);

        assertEquals(1, GrugModsWatcher.reportedChangeCount(path));
    }
}
