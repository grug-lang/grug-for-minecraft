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
        Files.writeString(game.resolve("grug_mods_state.json"), "{\"mods\": {\"b\": false}}");
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
    void wrapsAtSpacesAndKeepsALongWordWhole() {
        assertEquals(
                List.of("one two", "three", "enormousword", "x"),
                GrugModsScreenModel.wrap("one two three enormousword x", 8, String::length));
        assertEquals(List.of("short"), GrugModsScreenModel.wrap("short", 8, String::length));
    }

    @Test
    void aModThatAppearsWhileTheScreenIsOpenNeedsARestart() throws IOException {
        GrugModsScreenModel model = model(3);
        Files.createDirectories(game.resolve("grug_mods/d"));

        model.click(0);
        model.click(0);

        assertEquals(List.of("a: ON", "b: OFF", "c: ON"), model.labels());
        assertTrue(model.restartNeeded());
    }

    /** A catalogue holding the mod remote, over a game with no other mod. */
    private GrugModsScreenModel remoteModel(byte[] zip, String sha) throws IOException {
        String index = "{\"mods\":{\"remote\":{\"zip\":\"r.zip\",\"sha256\":\"" + sha + "\"}}}";
        GrugCatalogue catalogue =
                new GrugCatalogue(
                        url -> {
                            if (zip == null) throw new IOException("offline");
                            return url.endsWith("index.json") ? index.getBytes() : zip;
                        },
                        "http://x/index.json");
        GrugModManager manager =
                new GrugModManager(
                        game.toFile(),
                        name -> new ByteArrayInputStream(new byte[0]),
                        mod -> mod.equals("remote") ? "Sure?" : null,
                        catalogue);
        return new GrugModsScreenModel(manager, 5);
    }

    @Test
    void aCatalogueModIsDownloadedWhenTurnedOn() throws Exception {
        byte[] zip = GrugCatalogueTest.zip("remote/about.json", "{}");
        GrugModsScreenModel model = remoteModel(zip, GrugCatalogueTest.sha256(zip));
        assertEquals(List.of("remote: OFF (download)"), model.labels());
        assertNull(model.error());

        model.click(0);
        assertEquals("remote: Sure?", model.popup());
        model.accept();

        assertEquals(List.of("remote: ON"), model.labels());
        assertTrue(Files.isRegularFile(game.resolve("grug_mods/remote/about.json")));
        assertTrue(model.restartNeeded());
        assertNull(model.error());
    }

    @Test
    void aCatalogueModThatIsInstalledOrBundledIsNotDownloaded() throws Exception {
        byte[] zip = GrugCatalogueTest.zip("remote/about.json", "{}");
        Files.createDirectories(game.resolve("grug_mods/remote"));
        GrugModsScreenModel installed = remoteModel(zip, "0".repeat(64));

        assertEquals(List.of("remote: ON"), installed.labels());

        Files.delete(game.resolve("grug_mods/remote"));
        GrugCatalogue catalogue =
                new GrugCatalogue(
                        url ->
                                url.endsWith("index.json")
                                        ? ("{\"mods\":{\"remote\":{\"zip\":\"r.zip\",\"sha256\":\""
                                                        + "0".repeat(64)
                                                        + "\"}}}")
                                                .getBytes()
                                        : zip,
                        "http://x/index.json");
        GrugModManager manager =
                new GrugModManager(
                        game.toFile(),
                        name ->
                                new ByteArrayInputStream(
                                        (name.endsWith("txt") ? "remote/about.json\n" : "")
                                                .getBytes()),
                        mod -> null,
                        catalogue);
        assertEquals(List.of("remote: OFF"), new GrugModsScreenModel(manager, 5).labels());
    }

    @Test
    void aFailedDownloadIsShownAndTheModStaysOff() throws Exception {
        byte[] zip = GrugCatalogueTest.zip("remote/about.json", "{}");
        GrugModsScreenModel model = remoteModel(zip, "0".repeat(64));

        model.click(0);
        model.accept();

        assertEquals(List.of("remote: OFF (download)"), model.labels());
        assertTrue(model.error().startsWith("Could not install remote: "), model.error());
        assertFalse(model.restartNeeded());
    }

    @Test
    void anErrorIsBrokenIntoAFewLines() throws Exception {
        GrugModsScreenModel none = model(3);
        assertEquals(List.of(), none.errorLines(10, String::length));

        GrugModsScreenModel model = remoteModel(null, "0".repeat(64));
        // "Could not read the mod catalogue: offline" at ten characters a line.
        assertEquals(
                List.of("Could not", "read the", "mod..."), model.errorLines(10, String::length));
        assertEquals(
                List.of("Could not read the mod catalogue: offline"),
                model.errorLines(100, String::length));
    }

    @Test
    void aCatalogueThatCannotBeReadIsShown() throws Exception {
        GrugModsScreenModel model = remoteModel(null, "0".repeat(64));

        assertTrue(model.error().startsWith("Could not read the mod catalogue"), model.error());
        assertEquals(List.of(), model.labels());
    }
}
