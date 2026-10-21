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
}
