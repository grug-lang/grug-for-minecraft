package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pins that an edited overlay script is made again, and that no other overlay is, which no game
 * test can observe: a capture shows whether an overlay drew, not whether the entity behind it was
 * rebuilt. See #881.
 *
 * <p>The report is handed to {@link GrugModsWatcher#applyReport}, the seam a hot reload arrives
 * through, and the entities are counted through {@link GrugOverlayScripts}' seam, so no grug state
 * and no screen are needed.
 */
class GrugOverlayScriptsTest {
    private static final String PREFIX = "overlay_test/";

    private static final String MOD = "cov";
    private static final String NAME = "cov_overlay";
    private static final String OTHER = "other_overlay";
    private static final String PATH = PREFIX + "code/" + NAME + "-Overlay.grug";

    private Object savedAdapter;
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void installAdapterAndForgetEarlierTests() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> null));
        GrugOverlayScripts.resetForTest();
    }

    @AfterEach
    void restoreAdapterAndScripts() throws Exception {
        GrugOverlayScripts.resetForTest();
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
    }

    /** Removes the file id the fake reports left in the watcher's static state. */
    @AfterEach
    void forgetTheFakeReports() {
        GrugModsWatcher.fileIds.keySet().removeIf(path -> path.startsWith(PREFIX));
    }

    private static FileInfo overlay(String path, String entityName, long fileId) {
        return new FileInfo(
                path, entityName + "-Overlay.grug", MOD, "Overlay", entityName, fileId, null);
    }

    /** Counts what a reload makes, drops and looks up, handing out a fresh handle every time. */
    private static final class Recorder implements GrugOverlayScripts.Entities {
        final List<Long> created = new ArrayList<>();
        final List<Long> destroyed = new ArrayList<>();
        final List<String> exports = new ArrayList<>();
        private final long refusedFileId;
        private final String refusedExport;
        private long nextHandle = 100;

        Recorder(long refusedFileId, String refusedExport) {
            this.refusedFileId = refusedFileId;
            this.refusedExport = refusedExport;
        }

        @Override
        public long create(long fileId, List<GrugObject> owner) {
            created.add(fileId);
            if (fileId == refusedFileId) return 0;
            return nextHandle++;
        }

        @Override
        public void destroy(long entityHandle) {
            destroyed.add(entityHandle);
        }

        @Override
        public long exportFnId(String entityType, String fnName) {
            exports.add(entityType + "." + fnName);
            if ((entityType + "." + fnName).equals(refusedExport)) {
                return Grug.INVALID_GRUG_EXPORT_FN_ID;
            }
            return exports.size();
        }
    }

    private static Recorder record() {
        return record(-1L);
    }

    private static Recorder record(long refusedFileId) {
        Recorder recorder = new Recorder(refusedFileId, null);
        GrugOverlayScripts.useEntities(recorder);
        return recorder;
    }

    private static Recorder recordRefusingExport(String export) {
        Recorder recorder = new Recorder(-1L, export);
        GrugOverlayScripts.useEntities(recorder);
        return recorder;
    }

    private static void init(Map<String, Long> files, Map<String, String> mods) {
        GrugOverlayScripts.init(files, mods);
    }

    private static Map<String, Long> twoOverlays() {
        Map<String, Long> files = new TreeMap<>();
        files.put(NAME, 4L);
        files.put(OTHER, 5L);
        return files;
    }

    private static Map<String, String> twoMods() {
        Map<String, String> mods = new TreeMap<>();
        mods.put(NAME, "a");
        mods.put(OTHER, "b");
        return mods;
    }

    private static List<String> namesOf(List<GrugOverlayScripts.Script> scripts) {
        List<String> names = new ArrayList<>();
        for (GrugOverlayScripts.Script script : scripts) names.add(script.name);
        return names;
    }

    @Test
    void anEditedOverlayIsMadeAgainWithoutTouchingAnotherOverlaysState() {
        Recorder recorder = record();
        init(twoOverlays(), twoMods());

        List<GrugOverlayScripts.Script> before = GrugOverlayScripts.scripts();
        assertEquals(Arrays.asList(4L, 5L), recorder.created);
        assertEquals(Arrays.asList(NAME, OTHER), namesOf(before));
        GrugOverlayScripts.Script other = before.get(1);
        // Every export of both files is looked up on its entity.
        assertEquals(16, recorder.exports.size());

        GrugModsWatcher.applyReport(
                new FileInfo[] {overlay(PATH, NAME, 7L)}, new String[0], errors::add);

        List<GrugOverlayScripts.Script> after = GrugOverlayScripts.scripts();
        assertTrue(errors.isEmpty(), errors.toString());
        // Only the edited file's entity is dropped, and it is made again from the id the report
        // carries. The other mod's overlay keeps its entity, and so its member state.
        assertEquals(Collections.singletonList(100L), recorder.destroyed);
        assertEquals(Arrays.asList(4L, 5L, 7L), recorder.created);
        assertEquals(Arrays.asList(NAME, OTHER), namesOf(after));
        assertEquals(102L, after.get(0).entityHandle);
        assertEquals(MOD, after.get(0).mod);
        assertSame(other, after.get(1), "another mod's overlay is not made again");
        assertEquals(
                24, recorder.exports.size(), "the edited overlay's exports are looked up again");
    }

    @Test
    void aModsBindingIsAnsweredByItsFirstOverlay() {
        Map<String, Long> files = twoOverlays();
        Map<String, String> mods = new TreeMap<>();
        mods.put(NAME, "a");
        mods.put(OTHER, "a");
        record();
        init(files, mods);

        GrugOverlayScripts.Script first = GrugOverlayScripts.bindingScript("a");
        assertEquals(NAME, first.name);
        List<GrugOverlayScripts.Script> answering = GrugOverlayScripts.bindingScripts();
        assertEquals(1, answering.size(), "one overlay per mod answers its mod's bindings");
        assertSame(first, answering.get(0));
        assertNull(GrugOverlayScripts.bindingScript("b"));
    }

    @Test
    void aModWithoutAWorldBindingExportAnswersNoBinding() {
        recordRefusingExport("Overlay.world_binding");
        init(twoOverlays(), twoMods());

        assertTrue(GrugOverlayScripts.bindingScripts().isEmpty());
        assertNull(GrugOverlayScripts.bindingScript("a"));
    }

    @Test
    void anOverlayFileThatFailedToCompileKeepsTheOverlayItHad() {
        Recorder recorder = record();
        init(twoOverlays(), twoMods());
        GrugOverlayScripts.scripts();

        GrugModsWatcher.applyReport(
                new FileInfo[] {overlay(PATH, NAME, Grug.INVALID_GRUG_FILE_ID)},
                new String[0],
                errors::add);

        List<GrugOverlayScripts.Script> after = GrugOverlayScripts.scripts();
        assertEquals(1, errors.size(), "the compile error is reported, as a block's is");
        assertTrue(errors.get(0).startsWith("Failed to hot-reload"), errors.get(0));
        assertTrue(recorder.destroyed.isEmpty(), "a broken edit does not drop the working overlay");
        assertEquals(Arrays.asList(4L, 5L), recorder.created);
        assertEquals(Arrays.asList(NAME, OTHER), namesOf(after));
    }

    @Test
    void anOverlayWhoseEntityCouldNotBeMadeIsLeftOut() {
        Recorder recorder = record(4L);
        init(twoOverlays(), twoMods());

        List<GrugOverlayScripts.Script> made = GrugOverlayScripts.scripts();
        assertEquals(Collections.singletonList(OTHER), namesOf(made));
        assertEquals(Arrays.asList(4L, 5L), recorder.created);
        assertEquals(
                8,
                recorder.exports.size(),
                "no export is looked up on an entity that was not made");
    }

    @Test
    void anOverlayAddedWhileTheGameRunsIsMadeOnTheNextFrame() {
        Recorder recorder = record();
        Map<String, Long> files = new TreeMap<>();
        files.put(NAME, 4L);
        Map<String, String> mods = new TreeMap<>();
        mods.put(NAME, MOD);
        init(files, mods);
        assertEquals(Arrays.asList(NAME), namesOf(GrugOverlayScripts.scripts()));

        String added = PREFIX + "code/added-Overlay.grug";
        GrugModsWatcher.applyReport(
                new FileInfo[] {overlay(added, "added", 9L)}, new String[0], errors::add);

        List<GrugOverlayScripts.Script> after = GrugOverlayScripts.scripts();
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(Arrays.asList(4L, 9L), recorder.created);
        assertEquals(Arrays.asList("added", NAME), namesOf(after));
        assertEquals(100L, after.get(1).entityHandle);
        assertTrue(recorder.destroyed.isEmpty(), "a new file drops no other overlay");
    }

    @Test
    void anOverlayFixedAfterFailingAtLoadIsMadeWhenItCompiles() {
        Recorder recorder = record();
        // A file that failed at load is not in the loader's map, so only the watcher's report can
        // make it appear; this is the case a restart used to be needed for.
        init(new TreeMap<>(), new TreeMap<>());

        GrugModsWatcher.applyReport(
                new FileInfo[] {overlay(PATH, NAME, 8L)}, new String[0], errors::add);

        List<GrugOverlayScripts.Script> after = GrugOverlayScripts.scripts();
        assertEquals(Collections.singletonList(8L), recorder.created);
        assertEquals(Collections.singletonList(NAME), namesOf(after));
    }
}
