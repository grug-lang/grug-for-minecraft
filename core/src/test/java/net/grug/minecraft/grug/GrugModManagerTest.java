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
        // The catalogue is pointed at a port nothing listens on, so no test reaches the internet.
        System.setProperty(GrugCatalogue.URL_PROPERTY, "http://127.0.0.1:1/index.json");
        try {
            GrugModManager manager = GrugModManager.of(game.toFile());

            assertTrue(manager.list().stream().allMatch(e -> e.prompt() == null));
            assertNull(manager.list().get(0).prompt());
            assertTrue(manager.catalogueError().startsWith("Could not read the mod catalogue"));
        } finally {
            System.clearProperty(GrugCatalogue.URL_PROPERTY);
        }
    }

    @Test
    void stateThatIsNotTheExpectedShapeIsFatal() throws IOException {
        for (String bad :
                new String[] {
                    "[]", "{}", "{\"mods\": {\"a\": \"yes\"}}", "{\"mods\": {\"a\": []}}", "{"
                }) {
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

    @Test
    void aNameThatCouldLeaveTheManagedDirectoriesIsFatal() throws IOException {
        String[] bad = {"", ".", "..", "../x", "a/b", "a\\b", "c:", "a\u0000b"};
        for (String name : bad) {
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, () -> manager().setOn(name, true));
            assertTrue(error.getMessage().contains("is not a mod name"), error.getMessage());
            clearFatal();

            state("{\"mods\": {" + new com.google.gson.JsonPrimitive(name) + ": false}}");
            error = assertThrows(IllegalStateException.class, manager()::list, name);
            assertTrue(error.getMessage().contains("grug_mods_state.json"), error.getMessage());
            clearFatal();
        }
    }

    /** A catalogue of the mod remote, whose zip holds one file, or one that fails to be read. */
    private GrugModManager withCatalogue(boolean reachable, byte[] zip) {
        return withCatalogue(reachable, zip, hash(zip));
    }

    private GrugModManager withCatalogue(boolean reachable, byte[] zip, String indexedHash) {
        String index =
                "{\"mods\":{\"remote\":{\"zip\":\"remote.zip\",\"sha256\":\""
                        + indexedHash
                        + "\"}}}";
        GrugCatalogue catalogue =
                new GrugCatalogue(
                        url -> {
                            if (!reachable) throw new IOException("offline");
                            if (url.endsWith("index.json")) return index.getBytes();
                            return zip;
                        },
                        "http://x/index.json");
        return new GrugModManager(game.toFile(), jar(), mod -> null, catalogue);
    }

    private static String hash(byte[] data) {
        try {
            return GrugCatalogueTest.sha256(data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aCatalogueModIsListedAsDownloadableUntilItIsInstalled() throws Exception {
        GrugModManager manager =
                withCatalogue(true, GrugCatalogueTest.zip("remote/about.json", "{}"));

        GrugModManager.Entry remote = manager.list().get(manager.list().size() - 1);
        assertEquals("remote", remote.name());
        assertTrue(remote.downloadable());
        assertFalse(remote.installed());
        assertFalse(remote.on());
        assertNull(manager.catalogueError());

        manager.install("remote");

        assertTrue(Files.isRegularFile(mods().resolve("remote/about.json")));
        GrugModManager.Entry installed = manager.list().get(manager.list().size() - 1);
        assertTrue(installed.installed() && installed.on() && installed.downloadable());
    }

    @Test
    void aModTheCatalogueDoesNotHoldCannotBeInstalled() {
        GrugModManager manager = withCatalogue(true, new byte[0]);

        IOException error = assertThrows(IOException.class, () -> manager.install("ghost"));

        assertTrue(error.getMessage().contains("not in the catalogue"), error.getMessage());
    }

    @Test
    void aCatalogueThatCannotBeReadLeavesTheLocalModsAndSaysWhy() {
        GrugModManager manager = withCatalogue(false, new byte[0]);

        assertEquals("examplemod-b extra-b", summary(manager.list()));
        assertEquals("Could not read the mod catalogue: offline", manager.catalogueError());
    }

    @Test
    void aGameWithNoCatalogueHasNoError() {
        assertNull(manager().catalogueError());
    }

    @Test
    void aFailedInstallKeepsTheStateAsItWas() throws Exception {
        GrugModManager manager =
                withCatalogue(true, GrugCatalogueTest.zip("remote/about.json", "{}"));
        GrugModManager wrongHash =
                withCatalogue(
                        true, GrugCatalogueTest.zip("remote/about.json", "{}"), "0".repeat(64));

        assertThrows(IOException.class, () -> wrongHash.install("remote"));

        assertFalse(Files.exists(mods().resolve("remote")));
        assertFalse(Files.exists(game.resolve("grug_mods_state.json")));
        assertTrue(manager.list().stream().anyMatch(e -> e.name().equals("remote")));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
