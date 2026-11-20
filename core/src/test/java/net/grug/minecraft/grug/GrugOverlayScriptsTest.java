package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pins that an edited overlay script is made again, which no game test can observe: a capture shows
 * whether an overlay drew, not whether the entity behind it was rebuilt. See #881.
 *
 * <p>The report is handed to {@link GrugModsWatcher#applyReport}, the seam a hot reload arrives
 * through, and the entities are counted through {@link GrugOverlayScripts}' seam, so no grug state
 * and no screen are needed.
 */
class GrugOverlayScriptsTest {
    private static final String PREFIX = "overlay_test/";

    private static final String MOD = "cov";
    private static final String NAME = "cov_overlay";
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
        private long nextHandle = 100;

        Recorder(long refusedFileId) {
            this.refusedFileId = refusedFileId;
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
            return exports.size();
        }
    }

    private static Recorder record() {
        return record(-1L);
    }

    private static Recorder record(long refusedFileId) {
        Recorder recorder = new Recorder(refusedFileId);
        GrugOverlayScripts.useEntities(recorder);
        return recorder;
    }

    private static void init(Map<String, Long> files, Map<String, String> mods) {
        GrugOverlayScripts.init(files, mods);
    }

    private static Map<String, Long> filesOf(String name, long fileId) {
        Map<String, Long> files = new HashMap<>();
        files.put(name, fileId);
        return files;
    }

    private static Map<String, String> modsOf(String name, String mod) {
        Map<String, String> mods = new HashMap<>();
        mods.put(name, mod);
        return mods;
    }

    @Test
    void anEditedOverlayIsMadeAgainFromItsNewFile() {
        Recorder recorder = record();
        init(filesOf(NAME, 4L), modsOf(NAME, MOD));

        List<GrugOverlayScripts.Script> before = GrugOverlayScripts.scripts();
        assertEquals(Collections.singletonList(4L), recorder.created);
        assertEquals(100L, before.get(0).entityHandle);
        assertEquals(MOD, before.get(0).mod);
        // Every export of the new file is looked up on its entity.
        assertEquals(7, recorder.exports.size());

        GrugModsWatcher.applyReport(
                new FileInfo[] {overlay(PATH, NAME, 4L)}, new String[0], errors::add);

        List<GrugOverlayScripts.Script> after = GrugOverlayScripts.scripts();
        assertTrue(errors.isEmpty(), errors.toString());
        // The old entity is dropped rather than re-asked, and the new file makes a new one.
        assertEquals(Collections.singletonList(100L), recorder.destroyed);
        assertEquals(Arrays.asList(4L, 4L), recorder.created);
        assertEquals(101L, after.get(0).entityHandle);
        assertEquals(MOD, after.get(0).mod);
        assertEquals(14, recorder.exports.size(), "the exports are looked up again, not reused");
    }

    @Test
    void anOverlayFileThatFailedToCompileKeepsTheOverlayItHad() {
        Recorder recorder = record();
        init(filesOf(NAME, 4L), modsOf(NAME, MOD));
        GrugOverlayScripts.scripts();

        GrugModsWatcher.applyReport(
                new FileInfo[] {overlay(PATH, NAME, Grug.INVALID_GRUG_FILE_ID)},
                new String[0],
                errors::add);

        List<GrugOverlayScripts.Script> after = GrugOverlayScripts.scripts();
        assertEquals(1, errors.size(), "the compile error is reported, as a block's is");
        assertTrue(errors.get(0).startsWith("Failed to hot-reload"), errors.get(0));
        assertTrue(recorder.destroyed.isEmpty(), "a broken edit does not drop the working overlay");
        assertEquals(Collections.singletonList(4L), recorder.created);
        assertEquals(100L, after.get(0).entityHandle);
    }

    @Test
    void anOverlayWhoseEntityCouldNotBeMadeIsLeftOut() {
        Recorder recorder = record(4L);
        init(filesOf(NAME, 4L), modsOf(NAME, MOD));

        assertTrue(GrugOverlayScripts.scripts().isEmpty(), "a file with no entity is not asked");
        assertEquals(Collections.singletonList(4L), recorder.created);
        assertTrue(
                recorder.exports.isEmpty(),
                "no export is looked up on an entity that was not made");
    }

    @Test
    void anOverlayAddedWhileTheGameRunsIsMadeOnTheNextFrame() {
        Recorder recorder = record();
        init(new HashMap<>(), new HashMap<>());
        assertTrue(GrugOverlayScripts.scripts().isEmpty());

        String added = PREFIX + "code/added-Overlay.grug";
        GrugModsWatcher.applyReport(
                new FileInfo[] {overlay(added, "added", 9L)}, new String[0], errors::add);

        List<GrugOverlayScripts.Script> after = GrugOverlayScripts.scripts();
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(Collections.singletonList(9L), recorder.created);
        assertEquals(100L, after.get(0).entityHandle);
    }
}
