package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Covers which grug mods are on, and how the game follows the state at startup. */
class GrugModManagerTest {

    @TempDir Path game;

    @AfterEach
    void clearFatal() {
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal a test provoked, cleared so it cannot outlive it.
        }
    }

    /** A jar bundling the mods examplemod and extra, with the index the build writes. */
    private static GrugModsExtractor.Resources jar() {
        Map<String, String> files = new HashMap<>();
        files.put(GrugModsExtractor.INDEX, "examplemod/about.json\nextra/about.json\n");
        files.put("/mods/examplemod/about.json", "tutorial");
        files.put("/mods/extra/about.json", "extra");
        return name -> {
            String content = files.get(name);
            return content == null
                    ? null
                    : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        };
    }

    private GrugModManager manager() {
        return new GrugModManager(
                game.toFile(), jar(), mod -> mod.equals("extra") ? "Sure?" : null);
    }

    private Path mods() {
        return game.resolve("grug_mods");
    }

    private void state(String json) throws IOException {
        Files.writeString(game.resolve("grug_mods_state.json"), json);
    }

    private static String summary(List<GrugModManager.Entry> entries) {
        return entries.stream()
                .map(
                        e ->
                                e.name()
                                        + (e.on() ? "+" : "-")
                                        + (e.installed() ? "i" : "")
                                        + (e.bundled() ? "b" : ""))
                .collect(Collectors.joining(" "));
    }

    @Test
    void aGameThatNeverRanInstallsAndTurnsOnOnlyTheTutorial() throws IOException {
        manager().apply();

        assertTrue(Files.isRegularFile(mods().resolve("examplemod/about.json")));
        assertFalse(Files.exists(mods().resolve("extra")));
        assertEquals("examplemod+ib extra-b", summary(manager().list()));
        assertTrue(Files.isRegularFile(mods().resolve(".examples_generated.txt")));
    }

    @Test
    void aGameThatRanBeforeKeepsWhatIsInstalledAndTurnsNothingOn() throws IOException {
        Files.createDirectories(mods().resolve("examplemod"));
        Files.createDirectories(mods().resolve("extra"));
        Files.writeString(mods().resolve(".examples_generated.txt"), "old");

        manager().apply();

        assertEquals("examplemod+ib extra+ib", summary(manager().list()));
        assertFalse(Files.exists(game.resolve("grug_mods_state.json")));
    }

    @Test
    void turningABundledModOnInstallsItAtTheNextStart() {
        manager().apply();
        manager().setOn("extra", true);

        assertEquals("examplemod+ib extra+b", summary(manager().list()));
        manager().apply();

        assertEquals("extra", read(mods().resolve("extra/about.json")));
        assertEquals("examplemod+ib extra+ib", summary(manager().list()));
    }

    @Test
    void turningAModOffMovesItAndTurningItBackOnMovesItBack() throws IOException {
        manager().apply();
        Files.writeString(mods().resolve("examplemod/edited.txt"), "kept");

        manager().setOn("examplemod", false);
        manager().apply();

        assertFalse(Files.exists(mods().resolve("examplemod")));
        assertEquals("kept", read(game.resolve("grug_mods_off/examplemod/edited.txt")));
        assertEquals("examplemod-ib extra-b", summary(manager().list()));

        manager().setOn("examplemod", true);
        manager().apply();

        assertEquals("kept", read(mods().resolve("examplemod/edited.txt")));
        assertFalse(Files.exists(game.resolve("grug_mods_off/examplemod")));
    }

    @Test
    void aModTheStateNeverMentionsIsOnWhileItsFilesAreInstalled() throws IOException {
        manager().apply();
        Files.createDirectories(mods().resolve("downloaded"));
        Files.createDirectories(mods().resolve(".half.installing"));
        Files.writeString(mods().resolve("mod_api.json"), "{}");

        assertEquals("downloaded+i examplemod+ib extra-b", summary(manager().list()));
    }

    @Test
    void aTurnedOffModIsListedAfterItsDirectoryMoved() throws IOException {
        Files.createDirectories(game.resolve("grug_mods_off/parked"));

        assertEquals("examplemod-b extra-b parked-i", summary(manager().list()));
    }

    @Test
    void aModTurnedOnThatIsNeitherBundledNorInstalledStaysAbsent() throws IOException {
        state("{\"mods\": {\"ghost\": true}}");
        manager().apply();

        assertFalse(Files.exists(mods().resolve("ghost")));
        assertEquals("examplemod-b extra-b ghost+", summary(manager().list()));
    }

    @Test
    void aModTurnedOffAndAlreadyAbsentNeedsNoMove() throws IOException {
        state("{\"mods\": {\"extra\": false}}");
        manager().apply();

        assertFalse(Files.exists(game.resolve("grug_mods_off")));
    }

    @Test
    void aModInBothDirectoriesIsFatal() throws IOException {
        state("{\"mods\": {\"examplemod\": false}}");
        Files.createDirectories(mods().resolve("examplemod"));
        Files.createDirectories(game.resolve("grug_mods_off/examplemod"));

        IllegalStateException error = assertThrows(IllegalStateException.class, manager()::apply);

        assertTrue(error.getCause().getMessage().contains("is in both"), error.getMessage());
    }

    @Test
    void theEntryCarriesThePopupTheModNeedsBeforeItIsTurnedOn() {
        Map<String, String> prompts =
                manager().list().stream()
                        .filter(e -> e.prompt() != null)
                        .collect(
                                Collectors.toMap(
                                        GrugModManager.Entry::name, GrugModManager.Entry::prompt));

        assertEquals(Map.of("extra", "Sure?"), prompts);
    }

    @Test
    void aGameDirectoryOverTheClasspathBundlesNoPopup() {
        assertTrue(
                GrugModManager.of(game.toFile()).list().stream().allMatch(e -> e.prompt() == null));
        assertNull(GrugModManager.of(game.toFile()).list().get(0).prompt());
    }

    @Test
    void stateThatIsNotTheExpectedShapeIsFatal() throws IOException {
        for (String bad : new String[] {"[]", "{}", "{\"mods\": {\"a\": \"yes\"}}", "{"}) {
            state(bad);
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, manager()::list, bad);
            assertTrue(error.getMessage().contains("grug_mods_state.json"), error.getMessage());
            clearFatal();
        }
    }

    @Test
    void aStateFileThatCannotBeWrittenIsFatal() throws IOException {
        Files.createDirectories(game.resolve("grug_mods_state.json/in-the-way"));

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> manager().setOn("extra", true));

        assertTrue(error.getMessage().contains("Failed to write"), error.getMessage());
    }

    @Test
    void aJarWithoutAnIndexIsFatal() {
        GrugModManager broken = new GrugModManager(game.toFile(), name -> null, mod -> null);

        IllegalStateException error = assertThrows(IllegalStateException.class, broken::list);

        assertTrue(error.getMessage().contains("mods bundled"), error.getMessage());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
