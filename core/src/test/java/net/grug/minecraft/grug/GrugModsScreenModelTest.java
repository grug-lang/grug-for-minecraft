package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Covers what the mod manager's screen shows and how it answers clicks. */
class GrugModsScreenModelTest {

    @TempDir Path game;

    /** Three installed mods, a, b and c, of which b needs a popup before it is turned on. */
    private GrugModsScreenModel model(int pageSize) throws IOException {
        for (String name : new String[] {"a", "b", "c"}) {
            Files.createDirectories(game.resolve("grug_mods").resolve(name));
        }
        Files.writeString(
                game.resolve("grug_mods_state.json"), "{\"mods\": {\"b\": {\"on\": false}}}");
        GrugModManager manager =
                new GrugModManager(
                        game.toFile(),
                        name -> new ByteArrayInputStream(new byte[0]),
                        mod -> mod.equals("b") ? "Unsafe." : null);
        return new GrugModsScreenModel(manager, pageSize);
    }

    @Test
    void listsAPageOfButtons() throws IOException {
        assertEquals(List.of("a: ON", "b: OFF", "c: ON"), model(3).labels());
    }

    @Test
    void pagesThroughTheMods() throws IOException {
        GrugModsScreenModel model = model(2);

        assertEquals(List.of("a: ON", "b: OFF"), model.labels());
        assertFalse(model.hasPrevious());
        assertTrue(model.hasNext());
        model.previous();
        model.next();
        assertEquals(List.of("c: ON"), model.labels());
        assertTrue(model.hasPrevious());
        assertFalse(model.hasNext());
        model.next();
        assertEquals(List.of("c: ON"), model.labels());
        model.previous();
        assertEquals(List.of("a: ON", "b: OFF"), model.labels());
    }

    @Test
    void aClickTurnsAModOffAndAsksForRestart() throws IOException {
        GrugModsScreenModel model = model(3);
        assertFalse(model.restartNeeded());

        model.click(0);

        assertEquals(List.of("a: OFF", "b: OFF", "c: ON"), model.labels());
        assertTrue(model.restartNeeded());
        model.click(0);
        assertFalse(model.restartNeeded());
    }

    @Test
    void aModThatNeedsAPopupBlocksTheScreenUntilAnswered() throws IOException {
        GrugModsScreenModel model = model(3);

        model.click(1);

        assertEquals("b: Unsafe.", model.popup());
        assertEquals("b: OFF", model.labels().get(1));
        model.click(0);
        assertEquals("a: ON", model.labels().get(0));

        model.decline();
        assertNull(model.popup());
        assertEquals("b: OFF", model.labels().get(1));
        assertFalse(model.restartNeeded());

        model.click(1);
        model.accept();
        assertNull(model.popup());
        assertEquals("b: ON", model.labels().get(1));
        assertTrue(model.restartNeeded());
    }

    @Test
    void answeringWithoutAPopupDoesNothing() throws IOException {
        GrugModsScreenModel model = model(3);

        model.accept();
        model.decline();

        assertFalse(model.restartNeeded());
    }

    @Test
    void turningAModOffNeedsNoPopup() throws IOException {
        GrugModsScreenModel model = model(3);
        model.click(1);
        model.accept();

        model.click(1);

        assertNull(model.popup());
        assertEquals("b: OFF", model.labels().get(1));
    }

    @Test
    void wrapsAtSpacesAndCutsAWordWiderThanALine() {
        assertEquals(
                List.of("one two", "three", "enormous", "word x"),
                GrugModsScreenModel.wrap("one two three enormousword x", 8, String::length));
        assertEquals(
                List.of("abcdefgh", "ijklmnop", "qr"),
                GrugModsScreenModel.wrap("abcdefghijklmnopqr", 8, String::length));
        assertEquals(List.of("a", "b", "c"), GrugModsScreenModel.wrap("abc", 0, String::length));
        assertEquals(List.of("short"), GrugModsScreenModel.wrap("short", 8, String::length));
    }

    @Test
    void aModThatAppearsFromOutsideWhileTheScreenIsOpenIsNotAChangeOfTheScreen()
            throws IOException {
        GrugModsScreenModel model = model(3);
        Files.createDirectories(game.resolve("grug_mods/d"));

        model.click(0);
        model.click(0);

        assertEquals(List.of("a: ON", "b: OFF", "c: ON"), model.labels());
        assertFalse(model.restartNeeded());
    }

    /** A catalogue holding the mod remote, over a game with no other mod, and its waiting tasks. */
    private GrugModsScreenModel remoteModel(
            FakeCatalogue fake, FakeCatalogue.Waiting waiting, boolean startLoaded) {
        GrugModManager manager =
                new GrugModManager(
                        game.toFile(),
                        name -> new ByteArrayInputStream(new byte[0]),
                        mod -> mod.equals("remote") ? "Sure?" : null,
                        fake.catalogue(),
                        waiting);
        GrugModsScreenModel model = new GrugModsScreenModel(manager, 5);
        if (startLoaded) {
            waiting.runAll();
            assertTrue(model.poll());
        }
        return model;
    }

    private FakeCatalogue remote(boolean goodHash, boolean reachable) throws Exception {
        byte[] zip = GrugCatalogueTest.zip("remote/about.json", "{}");
        String hash = goodHash ? GrugCatalogueTest.sha256(zip) : "0".repeat(64);
        return new FakeCatalogue(zip, hash, reachable);
    }

    private static GrugModsExtractor.Resources jarOf(String other) {
        return name ->
                name.equals(GrugModsExtractor.INDEX)
                        ? new ByteArrayInputStream("other/about.json\n".getBytes())
                        : name.equals("/mods/other/about.json")
                                ? new ByteArrayInputStream(other.getBytes())
                                : null;
    }

    private static FakeCatalogue release(String content, boolean goodHash) throws Exception {
        byte[] zip = GrugCatalogueTest.zip("remote/about.json", content);
        return new FakeCatalogue(
                zip, goodHash ? GrugCatalogueTest.sha256(zip) : "0".repeat(64), true);
    }

    /** remote from a release and other from the jar are installed, at version 1. */
    private void installBoth() throws Exception {
        GrugModManager first =
                new GrugModManager(
                        game.toFile(),
                        jarOf("1"),
                        mod -> null,
                        release("1", true).catalogue(),
                        Runnable::run);
        first.loadCatalogue();
        assertNull(first.startInstall("remote").error());
        first.setOn("other", true);
        first.apply();
    }

    /** The screen after the jar and the release moved on to version 2. */
    private GrugModsScreenModel updateModel(FakeCatalogue.Waiting waiting, boolean goodHash)
            throws Exception {
        GrugModManager manager =
                new GrugModManager(
                        game.toFile(),
                        jarOf("2"),
                        mod -> null,
                        release("2", goodHash).catalogue(),
                        waiting);
        GrugModsScreenModel model = new GrugModsScreenModel(manager, 5);
        waiting.runAll();
        assertTrue(model.poll());
        return model;
    }

    @Test
    void anUpdateIsDownloadedOnItsButtonAndAsksForARestart() throws Exception {
        installBoth();
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModsScreenModel model = updateModel(waiting, true);
        assertEquals(List.of(true, true), model.updates());
        assertEquals("Update all (2)", model.updateAllLabel());
        assertFalse(model.restartNeeded());

        model.clickUpdate(1); // remote, after other
        model.clickUpdate(0); // ignored: a download runs
        model.clickUpdateAll(); // ignored too
        assertTrue(model.busy());
        assertTrue(model.status().startsWith("Updating remote: "), model.status());
        waiting.runAll();
        assertTrue(model.poll());

        assertFalse(model.busy());
        assertNull(model.error());
        assertEquals(List.of(true, false), model.updates());
        assertEquals("Update all (1)", model.updateAllLabel());
        assertTrue(model.restartNeeded());

        model.clickUpdateAll(); // only the mod left
        waiting.runAll();
        assertTrue(model.poll());
        model.clickUpdateAll(); // none left
        assertFalse(model.busy());
    }

    @Test
    void updateAllRunsTheDownloadsOneAfterAnother() throws Exception {
        installBoth();
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModsScreenModel model = updateModel(waiting, true);

        model.clickUpdateAll();
        waiting.runAll();
        assertTrue(model.poll());
        assertTrue(model.busy()); // the second one has started
        waiting.runAll();
        assertTrue(model.poll());

        assertFalse(model.busy());
        assertNull(model.updateAllLabel());
        assertEquals(List.of(false, false), model.updates());
    }

    @Test
    void updateAllStopsAtTheFirstFailure() throws Exception {
        installBoth();
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModsScreenModel model = updateModel(waiting, false);

        model.clickUpdateAll();
        waiting.runAll();
        assertTrue(model.poll()); // other worked, remote started
        waiting.runAll();
        assertTrue(model.poll());

        assertFalse(model.busy());
        assertTrue(model.error().startsWith("Could not update remote: "), model.error());
        assertEquals(List.of(false, true), model.updates());
    }

    @Test
    void anUpdateThatReplacesChangedFilesAsksFirst() throws Exception {
        installBoth();
        Files.writeString(game.resolve("grug_mods/remote/about.json"), "mine");
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModsScreenModel model = updateModel(waiting, true);

        model.clickUpdate(1);
        assertFalse(model.busy());
        assertEquals(
                "remote has files you changed, which the update replaces. Update anyway?",
                model.popup());
        model.click(0); // ignored: a popup blocks the rest
        model.decline();
        assertNull(model.popup());
        assertFalse(model.busy());
        model.clickUpdate(1);
        model.accept();
        assertTrue(model.busy());
        waiting.runAll();
        assertTrue(model.poll());
        assertEquals(List.of(true, false), model.updates());
    }

    @Test
    void updateAllNamesEveryChangedModInOnePopup() throws Exception {
        installBoth();
        Files.writeString(game.resolve("grug_mods/remote/about.json"), "mine");
        Files.writeString(game.resolve("grug_mods/other/about.json"), "mine");
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModsScreenModel model = updateModel(waiting, true);

        model.clickUpdateAll();
        assertEquals(
                "other, remote have files you changed, which the update replaces. Update anyway?",
                model.popup());
        model.decline();
        assertFalse(model.busy());
        assertEquals("Update all (2)", model.updateAllLabel());
    }

    @Test
    void theCatalogueArrivesAfterTheScreenOpensWithoutAskingForARestart() throws Exception {
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModsScreenModel model = remoteModel(remote(true, true), waiting, false);

        assertEquals(List.of(), model.labels());
        assertEquals("Loading the mod catalogue...", model.status());
        assertFalse(model.poll());
        assertEquals(-1.0, model.progress());

        waiting.runAll();

        assertTrue(model.poll());
        assertFalse(model.poll());
        assertEquals(1, model.labels().size());
        assertTrue(model.labels().get(0).startsWith("remote: OFF (download, "));
        assertNull(model.status());
        assertFalse(model.restartNeeded());
    }

    @Test
    void aCatalogueModIsDownloadedWhileTheScreenShowsHowFarItIs() throws Exception {
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModsScreenModel model = remoteModel(remote(true, true), waiting, true);

        model.click(0);
        assertEquals("remote: Sure?", model.popup());
        model.accept();

        assertTrue(model.busy());
        assertEquals(0.0, model.progress());
        assertTrue(model.status().startsWith("Downloading remote: 0 B of "), model.status());
        model.click(0);
        assertFalse(model.poll());
        assertTrue(model.labels().get(0).startsWith("remote: OFF (download"));

        waiting.runAll();

        assertEquals(1.0, model.progress());
        assertTrue(model.poll());
        assertFalse(model.busy());
        assertEquals(List.of("remote: ON"), model.labels());
        assertTrue(Files.isRegularFile(game.resolve("grug_mods/remote/about.json")));
        assertTrue(model.restartNeeded());
        assertNull(model.error());
        assertNull(model.status());
    }

    @Test
    void aFailedDownloadIsShownAndTheModStaysOff() throws Exception {
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModsScreenModel model = remoteModel(remote(false, true), waiting, true);

        model.click(0);
        model.accept();
        waiting.runAll();

        assertTrue(model.poll());
        assertTrue(model.labels().get(0).startsWith("remote: OFF (download"));
        assertTrue(model.error().startsWith("Could not install remote: "), model.error());
        assertFalse(model.restartNeeded());
    }

    @Test
    void aCatalogueModThatIsInstalledIsNotDownloaded() throws Exception {
        Files.createDirectories(game.resolve("grug_mods/remote"));
        GrugModsScreenModel model =
                remoteModel(remote(true, true), new FakeCatalogue.Waiting(), true);

        assertEquals(List.of("remote: ON"), model.labels());
    }

    @Test
    void aCatalogueModThatIsBundledIsNotDownloaded() throws Exception {
        FakeCatalogue fake = remote(true, true);
        GrugModManager manager =
                new GrugModManager(
                        game.toFile(),
                        name ->
                                new ByteArrayInputStream(
                                        (name.endsWith(".txt") ? "remote/about.json\n" : "")
                                                .getBytes()),
                        mod -> null,
                        fake.catalogue(),
                        Runnable::run);

        GrugModsScreenModel model = new GrugModsScreenModel(manager, 5);
        model.poll();

        assertEquals(List.of("remote: OFF"), model.labels());
    }

    @Test
    void aCatalogueThatCannotBeReadIsShown() throws Exception {
        GrugModsScreenModel model =
                remoteModel(remote(true, false), new FakeCatalogue.Waiting(), true);

        assertTrue(model.error().startsWith("Could not read the mod catalogue"), model.error());
        assertEquals(List.of(), model.labels());
    }

    @Test
    void anErrorIsBrokenIntoAFewLines() throws Exception {
        GrugModsScreenModel none = model(3);
        assertEquals(List.of(), none.errorLines(10, String::length));

        GrugModsScreenModel model =
                remoteModel(remote(true, false), new FakeCatalogue.Waiting(), true);
        // "Could not read the mod catalogue: offline" at ten characters a line.
        assertEquals(
                List.of("Could not", "read the", "mod..."), model.errorLines(10, String::length));
        assertEquals(
                List.of("Could not read the mod catalogue: offline"),
                model.errorLines(100, String::length));
    }

    @Test
    void sizesAreShownInBytesKibMibAndGib() {
        assertEquals("0 B", GrugModsScreenModel.formatSize(0));
        assertEquals("1023 B", GrugModsScreenModel.formatSize(1023));
        assertEquals("1.0 KiB", GrugModsScreenModel.formatSize(1024));
        assertEquals("1.5 KiB", GrugModsScreenModel.formatSize(1536));
        assertEquals("2.0 MiB", GrugModsScreenModel.formatSize(2L * 1024 * 1024));
        assertEquals("3.4 GiB", GrugModsScreenModel.formatSize(3500L * 1024 * 1024));
        assertEquals("2048.0 GiB", GrugModsScreenModel.formatSize(2048L * 1024 * 1024 * 1024));
    }
}
