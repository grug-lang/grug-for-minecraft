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
        return jar("extra");
    }

    /** The same jar, in which the mod extra holds another file when {@code extra} differs. */
    private static GrugModsExtractor.Resources jar(String extra) {
        Map<String, String> files = new HashMap<>();
        files.put(GrugModsExtractor.INDEX, "examplemod/about.json\nextra/about.json\n");
        files.put("/mods/examplemod/about.json", "tutorial");
        files.put("/mods/extra/about.json", extra);
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
        state("{\"mods\": {\"ghost\": {\"on\": true}}}");
        manager().apply();

        assertFalse(Files.exists(mods().resolve("ghost")));
        assertEquals("examplemod-b extra-b ghost+", summary(manager().list()));
    }

    @Test
    void aModTurnedOffAndAlreadyAbsentNeedsNoMove() throws IOException {
        state("{\"mods\": {\"extra\": {\"on\": false}}}");
        manager().apply();

        assertFalse(Files.exists(game.resolve("grug_mods_off")));
    }

    @Test
    void aModInBothDirectoriesIsFatal() throws IOException {
        state("{\"mods\": {\"examplemod\": {\"on\": false}}}");
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
        GrugModManager manager = GrugModManager.of(game.toFile());

        assertTrue(manager.list().stream().allMatch(e -> e.prompt() == null));
        assertNull(manager.list().get(0).prompt());
    }

    @Test
    void stateThatIsNotTheExpectedShapeIsFatal() throws IOException {
        for (String bad :
                new String[] {
                    "[]",
                    "{}",
                    "{\"mods\": {\"a\": \"yes\"}}",
                    "{\"mods\": {\"a\": []}}",
                    "{",
                    "{\"mods\": {\"a\": {}}}",
                    "{\"mods\": {\"a\": {\"on\": true, \"installed\": {}}}}",
                    "{\"mods\": {\"a\": {\"on\": true, \"installed\":"
                            + " {\"source\": \"cdn\", \"sha256\": \"x\"}}}}"
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

            state("{\"mods\": {" + new com.google.gson.JsonPrimitive(name) + ": {\"on\": false}}}");
            error = assertThrows(IllegalStateException.class, manager()::list, name);
            assertTrue(error.getMessage().contains("grug_mods_state.json"), error.getMessage());
            clearFatal();
        }
    }

    private static byte[] remoteZip() throws IOException {
        return GrugCatalogueTest.zip("remote/about.json", "{}");
    }

    private GrugModManager withCatalogue(FakeCatalogue fake) {
        GrugModManager manager =
                new GrugModManager(
                        game.toFile(), jar(), mod -> null, fake.catalogue(), Runnable::run);
        manager.loadCatalogue();
        return manager;
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
        byte[] zip = remoteZip();
        GrugModManager manager = withCatalogue(new FakeCatalogue(zip, hash(zip), true));

        GrugModManager.Entry remote = manager.list().get(manager.list().size() - 1);
        assertEquals("remote", remote.name());
        assertTrue(remote.downloadable());
        assertEquals(zip.length, remote.downloadSize());
        assertFalse(remote.installed());
        assertFalse(remote.on());
        assertNull(manager.catalogueError());
        assertFalse(manager.catalogueLoading());

        GrugModManager.Install install = manager.startInstall("remote");

        assertTrue(install.finished());
        assertNull(install.error());
        assertEquals("remote", install.name());
        assertEquals(zip.length, install.total());
        assertEquals(zip.length, install.done());
        assertEquals(1.0, install.progress());
        assertTrue(Files.isRegularFile(mods().resolve("remote/about.json")));
        GrugModManager.Entry installed = manager.list().get(manager.list().size() - 1);
        assertTrue(installed.installed() && installed.on() && installed.downloadable());
    }

    @Test
    void aModTheCatalogueDoesNotHoldCannotBeInstalled() throws Exception {
        byte[] zip = remoteZip();
        GrugModManager manager = withCatalogue(new FakeCatalogue(zip, hash(zip), true));

        GrugModManager.Install install = manager.startInstall("ghost");

        assertTrue(install.finished());
        assertEquals("ghost is not in the catalogue", install.error());
        assertEquals(1.0, install.progress());
    }

    @Test
    void aCatalogueThatCannotBeReadLeavesTheLocalModsAndSaysWhy() throws Exception {
        GrugModManager manager =
                withCatalogue(new FakeCatalogue(remoteZip(), "0".repeat(64), false));

        assertEquals("examplemod-b extra-b", summary(manager.list()));
        assertEquals("Could not read the mod catalogue: offline", manager.catalogueError());
        assertFalse(manager.catalogueLoading());
    }

    @Test
    void aGameWithNoCatalogueHasNothingToLoad() {
        GrugModManager manager = manager();

        manager.loadCatalogue();

        assertNull(manager.catalogueError());
        assertFalse(manager.catalogueLoading());
    }

    @Test
    void theCatalogueIsReadOnceAndWhileItIsBeingReadTheListHasNoneOfIt() throws Exception {
        byte[] zip = remoteZip();
        FakeCatalogue fake = new FakeCatalogue(zip, hash(zip), true);
        FakeCatalogue.Waiting waiting = new FakeCatalogue.Waiting();
        GrugModManager manager =
                new GrugModManager(game.toFile(), jar(), mod -> null, fake.catalogue(), waiting);

        manager.loadCatalogue();
        manager.loadCatalogue();

        assertTrue(manager.catalogueLoading());
        assertEquals("examplemod-b extra-b", summary(manager.list()));
        waiting.runAll();
        assertFalse(manager.catalogueLoading());
        assertEquals(1, fake.fetches);
        assertEquals("examplemod-b extra-b remote-", summary(manager.list()));
    }

    @Test
    void aFailedInstallKeepsTheStateAsItWas() throws Exception {
        byte[] zip = remoteZip();
        GrugModManager manager = withCatalogue(new FakeCatalogue(zip, "0".repeat(64), true));

        GrugModManager.Install install = manager.startInstall("remote");

        assertTrue(install.error().contains("does not match"), install.error());
        assertFalse(Files.exists(mods().resolve("remote")));
        assertFalse(Files.exists(game.resolve("grug_mods_state.json")));
    }

    @Test
    void aStateFileThatCannotBeWrittenIsAnInstallError() throws Exception {
        byte[] zip = remoteZip();
        GrugModManager manager = withCatalogue(new FakeCatalogue(zip, hash(zip), true));
        Files.createDirectories(game.resolve("grug_mods_state.json/in-the-way"));

        GrugModManager.Install install = manager.startInstall("remote");

        assertTrue(install.finished());
        assertTrue(install.error().contains("Failed to write"), install.error());
    }

    @Test
    void theRealExecutorRunsTheRequestOnAThreadOfItsOwn() throws Exception {
        // The catalogue is pointed at a port nothing listens on, so no test reaches the internet.
        System.setProperty(GrugCatalogue.URL_PROPERTY, "http://127.0.0.1:1/index.json");
        try {
            GrugModManager manager = GrugModManager.of(game.toFile());
            manager.loadCatalogue();
            for (int i = 0; i < 500 && manager.catalogueLoading(); i++) Thread.sleep(10);

            assertFalse(manager.catalogueLoading());
            assertTrue(manager.catalogueError().startsWith("Could not read the mod catalogue"));
        } finally {
            System.clearProperty(GrugCatalogue.URL_PROPERTY);
        }
    }

    private GrugModManager managerOver(GrugModsExtractor.Resources jar, FakeCatalogue fake) {
        GrugModManager manager =
                new GrugModManager(
                        game.toFile(),
                        jar,
                        mod -> null,
                        fake == null ? null : fake.catalogue(),
                        Runnable::run);
        manager.loadCatalogue();
        return manager;
    }

    private static GrugModManager.Entry entry(GrugModManager manager, String name) {
        return manager.list().stream().filter(e -> e.name().equals(name)).findFirst().get();
    }

    /** Installs remote from a release whose zip holds {@code content}, as the screen would. */
    private void installRemote(String content) throws Exception {
        byte[] zip = GrugCatalogueTest.zip("remote/about.json", content);
        GrugModManager manager = managerOver(jar(), new FakeCatalogue(zip, hash(zip), true));
        assertNull(manager.startInstall("remote").error());
    }

    private FakeCatalogue release(String content, boolean goodHash) throws Exception {
        byte[] zip = GrugCatalogueTest.zip("remote/about.json", content);
        return new FakeCatalogue(zip, goodHash ? hash(zip) : "0".repeat(64), true);
    }

    @Test
    void aNewerReleaseOfAnInstalledModIsDownloadedAndReplacesItAtTheNextStart() throws Exception {
        installRemote("1");
        GrugModManager manager = managerOver(jar(), release("2", true));
        assertTrue(entry(manager, "remote").updatable());

        GrugModManager.Install install = manager.startUpdate("remote");

        assertNull(install.error());
        assertTrue(install.finished());
        assertEquals("1", read(mods().resolve("remote/about.json")));
        assertEquals("2", read(game.resolve("grug_mods_updates/remote/about.json")));
        assertFalse(entry(manager, "remote").updatable());
        assertTrue(entry(manager, "remote").updatePending());

        manager.apply();

        assertEquals("2", read(mods().resolve("remote/about.json")));
        assertFalse(Files.exists(game.resolve("grug_mods_updates/remote")));
        GrugModManager after = managerOver(jar(), release("2", true));
        assertFalse(entry(after, "remote").updatable());
        assertFalse(entry(after, "remote").updatePending());
        assertTrue(entry(after, "remote").on());
    }

    @Test
    void theRecordOfAnInstallIsTheHostsAndNotInTheModsFiles() throws Exception {
        installRemote("1");

        String state = read(game.resolve("grug_mods_state.json"));

        assertTrue(state.contains("\"source\":\"release\""), state);
        assertTrue(state.contains(hash(GrugCatalogueTest.zip("remote/about.json", "1"))), state);
        try (var files = Files.list(mods().resolve("remote"))) {
            assertEquals(
                    List.of("about.json"), files.map(f -> f.getFileName().toString()).toList());
        }
    }

    @Test
    void aGrantIsTheHostsRecordAndNotInTheModsFiles() throws Exception {
        installRemote("1");
        GrugModManager manager = managerOver(jar(), release("1", true));

        manager.grantUnsafeMode("remote");

        assertTrue(manager.isUnsafeModeGranted("remote"));
        assertTrue(
                read(game.resolve("grug_mods_state.json"))
                        .contains("\"unsafe_mode_granted\":true"));
        try (var files = Files.list(mods().resolve("remote"))) {
            assertEquals(
                    List.of("about.json"), files.map(f -> f.getFileName().toString()).toList());
        }
    }

    @Test
    void aGrantForAnInstalledModKeepsItOn() throws IOException {
        // The state writes every mod's on flag, so the entry a grant makes has to say what the game
        // is already doing with the mod rather than leave it reading as a request to turn it off.
        Files.createDirectories(mods().resolve("extra"));
        GrugModManager manager = manager();

        manager.grantUnsafeMode("extra");

        assertTrue(entry(manager, "extra").on());
        assertTrue(manager.isUnsafeModeGranted("extra"));
    }

    @Test
    void aGrantForABundledModThatIsNotInstalledDoesNotInstallIt() {
        GrugModManager manager = manager();

        manager.grantUnsafeMode("extra");
        manager.apply();

        assertFalse(entry(manager, "extra").on());
        assertFalse(Files.exists(mods().resolve("extra")));
    }

    @Test
    void aRevokedGrantIsForgotten() throws IOException {
        Files.createDirectories(mods().resolve("extra"));
        GrugModManager manager = manager();
        manager.grantUnsafeMode("extra");

        manager.revokeUnsafeMode("extra");

        assertFalse(manager.isUnsafeModeGranted("extra"));
        assertFalse(read(game.resolve("grug_mods_state.json")).contains("unsafe_mode_granted"));
    }

    @Test
    void revokingAGrantTheStateNeverHeldWritesNothing() {
        assertFalse(manager().isUnsafeModeGranted("extra"));

        manager().revokeUnsafeMode("extra");

        assertFalse(Files.exists(game.resolve("grug_mods_state.json")));
    }

    @Test
    void grantingAgainLeavesTheStateAsItIs() throws IOException {
        Files.createDirectories(mods().resolve("extra"));
        GrugModManager manager = manager();
        manager.grantUnsafeMode("extra");
        String once = read(game.resolve("grug_mods_state.json"));

        manager.grantUnsafeMode("extra");

        assertEquals(once, read(game.resolve("grug_mods_state.json")));
    }

    @Test
    void aGrantForANameThatCouldLeaveTheManagedDirectoriesIsFatal() {
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> manager().grantUnsafeMode("../x"));

        assertTrue(error.getMessage().contains("is not a mod name"), error.getMessage());
    }

    @Test
    void anUpdateTakesTheGrantWithTheFilesItReplaces() throws Exception {
        installRemote("1");
        GrugModManager manager = managerOver(jar(), release("2", true));
        manager.grantUnsafeMode("remote");
        assertTrue(manager.isUnsafeModeGranted("remote"));
        assertTrue(entry(manager, "remote").updatable());

        assertNull(manager.startUpdate("remote").error());
        manager.apply();

        assertFalse(manager.isUnsafeModeGranted("remote"));
        assertEquals("2", read(mods().resolve("remote/about.json")));
    }

    @Test
    void aReinstallClearsTheGrant() throws Exception {
        installRemote("1");
        GrugModManager manager = managerOver(jar(), release("1", true));
        manager.grantUnsafeMode("remote");
        assertTrue(manager.isUnsafeModeGranted("remote"));
        // The player deleted the files and installs the mod again, as the screen offers.
        GrugModsExtractor.deleteTree(mods().resolve("remote"));

        GrugModManager.Install install = manager.startInstall("remote");

        assertNull(install.error());
        assertFalse(manager.isUnsafeModeGranted("remote"));
    }

    @Test
    void anUpdateThatFailsTheHashCheckLeavesTheInstalledModAndStagesNothing() throws Exception {
        installRemote("1");
        GrugModManager manager = managerOver(jar(), release("2", false));

        GrugModManager.Install install = manager.startUpdate("remote");

        assertTrue(install.error().contains("does not match"), install.error());
        assertEquals("1", read(mods().resolve("remote/about.json")));
        assertFalse(Files.exists(game.resolve("grug_mods_updates/remote")));
        assertFalse(entry(manager, "remote").updatePending());
        manager.apply();
        assertEquals("1", read(mods().resolve("remote/about.json")));
    }

    @Test
    void anUpdateThatCannotBeDownloadedLeavesTheInstalledMod() throws Exception {
        installRemote("1");
        // A catalogue that answers the index and then fails the zip.
        byte[] zip = GrugCatalogueTest.zip("remote/about.json", "3");
        String index =
                "{\"mods\":{\"remote\":{\"zip\":\"remote.zip\",\"size\":"
                        + zip.length
                        + ",\"sha256\":\""
                        + hash(zip)
                        + "\"}}}";
        GrugCatalogue catalogue =
                new GrugCatalogue(
                        (url, limit, progress) -> {
                            if (url.endsWith("index.json")) {
                                return index.getBytes(StandardCharsets.UTF_8);
                            }
                            throw new IOException("connection reset");
                        },
                        "http://x/index.json");
        GrugModManager manager =
                new GrugModManager(game.toFile(), jar(), mod -> null, catalogue, Runnable::run);
        manager.loadCatalogue();

        GrugModManager.Install install = manager.startUpdate("remote");

        assertEquals("connection reset", install.error());
        assertFalse(Files.exists(game.resolve("grug_mods_updates/remote")));
        assertEquals("1", read(mods().resolve("remote/about.json")));
    }

    @Test
    void aModWithoutAnUpdateOrARecordHasNothingToUpdate() throws Exception {
        installRemote("1");
        GrugModManager same = managerOver(jar(), release("1", true));
        assertFalse(entry(same, "remote").updatable());
        assertEquals("remote has no update", same.startUpdate("remote").error());
        assertEquals("ghost has no update", same.startUpdate("ghost").error());

        // Installed before the record existed, and already on disk with the catalogue's mod.
        Files.createDirectories(mods().resolve("extra"));
        assertFalse(entry(managerOver(jar("changed"), null), "extra").updatable());
    }

    @Test
    void aModTheSourceNoLongerHoldsHasNoUpdate() throws Exception {
        installRemote("1");
        GrugModManager gone = managerOver(jar(), null);
        assertFalse(entry(gone, "remote").updatable());

        state(
                "{\"mods\": {\"gone\": {\"on\": true, \"installed\":"
                        + " {\"source\": \"jar\", \"sha256\": \"x\"}}}}");
        Files.createDirectories(mods().resolve("gone"));
        assertFalse(entry(managerOver(jar(), null), "gone").updatable());
    }

    @Test
    void aBundledModThatANewerJarChangedIsUpdatedFromTheJar() throws Exception {
        manager().setOn("extra", true);
        manager().apply();
        assertEquals("extra", read(mods().resolve("extra/about.json")));
        assertFalse(entry(managerOver(jar(), null), "extra").updatable());

        GrugModManager newer = managerOver(jar("extra 2"), null);
        assertTrue(entry(newer, "extra").updatable());
        GrugModManager.Install install = newer.startUpdate("extra");

        assertNull(install.error());
        assertEquals(1.0, install.progress());
        assertEquals("extra", read(mods().resolve("extra/about.json")));
        newer.apply();
        assertEquals("extra 2", read(mods().resolve("extra/about.json")));
        assertFalse(entry(managerOver(jar("extra 2"), null), "extra").updatable());
    }

    @Test
    void anUpdateOfAModThatIsOffReplacesItWhereItWaits() throws Exception {
        manager().setOn("extra", true);
        manager().apply();
        manager().setOn("extra", false);
        manager().apply();
        GrugModManager newer = managerOver(jar("extra 2"), null);

        assertNull(newer.startUpdate("extra").error());
        newer.apply();

        assertEquals("extra 2", read(game.resolve("grug_mods_off/extra/about.json")));
        assertFalse(Files.exists(mods().resolve("extra")));
        assertFalse(entry(newer, "extra").on());
    }

    @Test
    void anUpdateWhoseFilesOrInstalledModAreGoneIsDropped() throws Exception {
        String pending =
                "{\"mods\": {\"extra\": {\"on\": true, \"installed\": {\"source\": \"jar\","
                        + " \"sha256\": \"a\"}, \"update\": {\"source\": \"jar\", \"sha256\":"
                        + " \"b\"}}}}";
        state(pending);
        Files.createDirectories(mods().resolve("extra"));

        manager().apply(); // no staged files

        assertFalse(read(game.resolve("grug_mods_state.json")).contains("update"));
        assertTrue(Files.isDirectory(mods().resolve("extra")));

        state(pending);
        Files.delete(mods().resolve("extra"));
        Files.createDirectories(game.resolve("grug_mods_updates/extra"));
        Files.writeString(game.resolve("grug_mods_updates/extra/about.json"), "x");

        manager().apply(); // staged files, but nothing to replace

        assertFalse(Files.exists(game.resolve("grug_mods_updates/extra")));
        assertEquals("extra", read(mods().resolve("extra/about.json")));
    }

    @Test
    void aModThePlayerDeletedHasNoUpdateToOfferAndNoFilesToCompare() throws Exception {
        installRemote("1");
        GrugModsExtractor.deleteTree(mods().resolve("remote"));
        GrugModManager newer = managerOver(jar(), release("2", true));

        GrugModManager.Entry gone = entry(newer, "remote");
        assertFalse(gone.installed());
        assertFalse(gone.updatable());
        assertFalse(gone.edited());
        assertEquals("remote has no update", newer.startUpdate("remote").error());
    }

    @Test
    void aStateFileThatCannotBeWrittenIsAnUpdateError() throws Exception {
        manager().setOn("extra", true);
        manager().apply();
        GrugModManager newer = managerOver(jar("extra 2"), null);
        Files.createDirectories(game.resolve("grug_mods_state.json.tmp/in-the-way"));

        GrugModManager.Install install = newer.startUpdate("extra");

        assertTrue(install.error().contains("Failed to write"), install.error());
    }

    @Test
    void aBundledFileThatCannotBeReadIsFatal() throws Exception {
        state(
                "{\"mods\": {\"extra\": {\"on\": true, \"installed\": {\"source\": \"jar\","
                        + " \"sha256\": \"a\"}}}}");
        Files.createDirectories(mods().resolve("extra"));
        GrugModsExtractor.Resources broken =
                name -> name.startsWith("/mods/") ? null : jar().open(name);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> managerOver(broken, null).list());

        assertTrue(error.getMessage().contains("mods bundled"), error.getMessage());
    }

    @Test
    void aModWhoseFilesWereChangedIsEditedOnlyOnceItHasAnUpdate() throws Exception {
        installRemote("1");
        GrugModManager same = managerOver(jar(), release("1", true));
        Files.writeString(mods().resolve("remote/about.json"), "mine");
        assertFalse(entry(same, "remote").edited());

        GrugModManager newer = managerOver(jar(), release("2", true));
        assertTrue(entry(newer, "remote").edited());

        Files.writeString(mods().resolve("remote/about.json"), "1");
        assertFalse(entry(newer, "remote").edited());
    }

    @Test
    void aTurnedOffModIsCheckedForEditsWhereItWaits() throws Exception {
        manager().setOn("extra", true);
        manager().apply();
        manager().setOn("extra", false);
        manager().apply();
        GrugModManager newer = managerOver(jar("extra 2"), null);
        assertFalse(entry(newer, "extra").edited());

        Files.writeString(game.resolve("grug_mods_off/extra/about.json"), "mine");

        assertTrue(entry(newer, "extra").edited());
    }

    @Test
    void aModWithoutAHashOfItsFilesIsNotEdited() throws Exception {
        state(
                "{\"mods\": {\"extra\": {\"on\": true, \"installed\": {\"source\": \"jar\","
                        + " \"sha256\": \"a\"}}}}");
        Files.createDirectories(mods().resolve("extra"));

        assertTrue(entry(managerOver(jar(), null), "extra").updatable());
        assertFalse(entry(managerOver(jar(), null), "extra").edited());
    }

    @Test
    void aDirectoryThatCannotBeReadIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugModManager.directoryDigest(game.resolve("missing").toFile()));

        assertTrue(error.getMessage().contains("Failed to read"), error.getMessage());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
