package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the mod file states a test can put a file into, and the recovery of a file an earlier run
 * was killed while disturbing.
 */
class GrugModFileStatesTest {

    /** The mods directory, and the records that must end up beside it rather than inside it. */
    @TempDir Path tmp;

    private File modsDir;

    private Path backupRoot;

    private void givenAModFile(String contents) throws IOException {
        modsDir = Files.createDirectories(tmp.resolve("mods")).toFile();
        Path code = Files.createDirectories(modsDir.toPath().resolve("mymod/code"));
        Files.write(code.resolve("thing-Block.grug"), contents.getBytes(StandardCharsets.UTF_8));
        backupRoot = tmp.resolve(".grug_test_backups");
    }

    private String path() {
        return "mymod/code/thing-Block.grug";
    }

    private Path modFile() {
        return modsDir.toPath().resolve("mymod/code/thing-Block.grug");
    }

    private String modFileText() throws IOException {
        return new String(Files.readAllBytes(modFile()), StandardCharsets.UTF_8);
    }

    @Test
    void invalidPutsTheMarkerAtTheTopAndRecordsThePristineText() throws IOException {
        givenAModFile("export init() {\n}\n");

        GrugModFileStates.set(modsDir, path(), "invalid");

        assertEquals("!\nexport init() {\n}\n", modFileText());
        assertEquals(
                "export init() {\n}\n",
                new String(
                        Files.readAllBytes(backupRoot.resolve(path() + ".grugbak")),
                        StandardCharsets.UTF_8));
    }

    @Test
    void noTrailingNewlineTakesTheLastOne() throws IOException {
        givenAModFile("export init() {\n}\n");

        GrugModFileStates.set(modsDir, path(), "no_trailing_newline");

        assertEquals("export init() {\n}", modFileText());
    }

    @Test
    void noTrailingNewlineLeavesAFileThatHasNoneAlone() throws IOException {
        givenAModFile("export init() {\n}");

        GrugModFileStates.set(modsDir, path(), "no_trailing_newline");

        assertEquals("export init() {\n}", modFileText());
    }

    @Test
    void noTrailingNewlineLeavesAnEmptyFileEmpty() throws IOException {
        // Reading the file as bytes rather than as text is what makes the length a thing to ask
        // about, so the empty file is the case that has to be answered rather than indexed into.
        modsDir = Files.createDirectories(tmp.resolve("mods")).toFile();
        Path assets = Files.createDirectories(modsDir.toPath().resolve("mymod/assets"));
        Files.write(assets.resolve("empty.png"), new byte[0]);
        backupRoot = tmp.resolve(".grug_test_backups");

        GrugModFileStates.set(modsDir, "mymod/assets/empty.png", "no_trailing_newline");

        assertEquals(0, Files.readAllBytes(assets.resolve("empty.png")).length);
    }

    /** Bytes no charset decodes back to themselves, so a text record cannot hold them. */
    private static final byte[] PNG_HEADER = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0x00, (byte) 0xFF, (byte) 0xFE
    };

    private String assetPath() {
        return "mymod/assets/mymod/textures/block/stone.png";
    }

    /**
     * A PNG in the assets tree, with the sibling the {@code "swapped"} state copies in beside it.
     */
    private void givenABinaryAsset(byte[] original, byte[] replacement) throws IOException {
        modsDir = Files.createDirectories(tmp.resolve("mods")).toFile();
        Path textures =
                Files.createDirectories(
                        modsDir.toPath().resolve("mymod/assets/mymod/textures/block"));
        Files.write(textures.resolve("stone.png"), original);
        Files.write(textures.resolve("stone.swap.png"), replacement);
        backupRoot = tmp.resolve(".grug_test_backups");
    }

    @Test
    void swappedCopiesTheSiblingInAndNormalRestoresTheBytesExactly() throws IOException {
        byte[] original = new byte[] {'a', (byte) 0xFF, 'b'};
        byte[] replacement = new byte[] {'c', (byte) 0x80, 'd', (byte) 0xFE};
        givenABinaryAsset(original, replacement);
        Path asset = modsDir.toPath().resolve(assetPath());

        GrugModFileStates.set(modsDir, assetPath(), "swapped");

        assertArrayEquals(replacement, Files.readAllBytes(asset));

        GrugModFileStates.set(modsDir, assetPath(), "normal");

        // Byte for byte, not merely equal once decoded: an asset that round-tripped through a
        // charset came back holding replacement characters and no longer decoded as an image.
        assertArrayEquals(original, Files.readAllBytes(asset));
    }

    @Test
    void swappedLeavesTheSiblingItCopiedFromAlone() throws IOException {
        givenABinaryAsset(new byte[] {'a'}, PNG_HEADER);
        Path sibling = modsDir.toPath().resolve("mymod/assets/mymod/textures/block/stone.swap.png");

        GrugModFileStates.set(modsDir, assetPath(), "swapped");
        GrugModFileStates.set(modsDir, assetPath(), "normal");

        // The sibling is the fixture the next run swaps in with, so putting the file it was copied
        // over back must not spend it.
        assertArrayEquals(PNG_HEADER, Files.readAllBytes(sibling));
    }

    @Test
    void swappedLooksBesideAFileWhoseNameHasNoExtension() throws IOException {
        modsDir = Files.createDirectories(tmp.resolve("mods")).toFile();
        Path mod = Files.createDirectories(modsDir.toPath().resolve("mymod"));
        Files.write(mod.resolve("LICENSE"), "the original".getBytes(StandardCharsets.UTF_8));
        Files.write(
                mod.resolve("LICENSE.swap"), "the replacement".getBytes(StandardCharsets.UTF_8));
        backupRoot = tmp.resolve(".grug_test_backups");

        GrugModFileStates.set(modsDir, "mymod/LICENSE", "swapped");

        assertEquals(
                "the replacement",
                new String(Files.readAllBytes(mod.resolve("LICENSE")), StandardCharsets.UTF_8));
    }

    @Test
    void swappedWithNoSiblingToSwapInFails() throws IOException {
        givenABinaryAsset(PNG_HEADER, PNG_HEADER);
        Files.delete(modsDir.toPath().resolve("mymod/assets/mymod/textures/block/stone.swap.png"));

        // Carrying on would write back the file's own bytes, which reports no change to the engine
        // and leaves a test waiting for a hot reload that can never arrive.
        assertThrows(
                IOException.class, () -> GrugModFileStates.set(modsDir, assetPath(), "swapped"));
    }

    @Test
    void aRunKilledWhileSwappingAnAssetIsRepairedOnTheNextStartup() throws IOException {
        givenABinaryAsset(PNG_HEADER, new byte[] {(byte) 0xFE, 0x00});
        List<String> log = new ArrayList<>();

        GrugModFileStates.set(modsDir, assetPath(), "swapped");
        GrugModFileStates.restoreInterruptedRuns(modsDir, log::add);

        assertArrayEquals(PNG_HEADER, Files.readAllBytes(modsDir.toPath().resolve(assetPath())));
        assertEquals(1, log.size(), log.toString());
    }

    @Test
    void deletedRemovesTheFileAndRecordsThePristineText() throws IOException {
        givenAModFile("export init() {\n}\n");

        GrugModFileStates.set(modsDir, path(), "deleted");

        assertFalse(Files.exists(modFile()));
        assertEquals(
                "export init() {\n}\n",
                new String(
                        Files.readAllBytes(backupRoot.resolve(path() + ".grugbak")),
                        StandardCharsets.UTF_8));
    }

    @Test
    void normalRestoresADeletedFileAndDropsTheRecord() throws IOException {
        givenAModFile("export init() {\n}\n");
        GrugModFileStates.set(modsDir, path(), "deleted");

        GrugModFileStates.set(modsDir, path(), "normal");

        assertEquals("export init() {\n}\n", modFileText());
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void deletingTheSameFileTwiceKeepsTheFirstRecord() throws IOException {
        givenAModFile("export init() {\n}\n");
        Path backup = backupRoot.resolve(path() + ".grugbak");
        GrugModFileStates.set(modsDir, path(), "deleted");
        Files.write(backup, "the first record".getBytes(StandardCharsets.UTF_8));

        GrugModFileStates.set(modsDir, path(), "deleted");

        assertFalse(Files.exists(modFile()));
        assertEquals(
                "the first record", new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
    }

    @Test
    void deletingAFileAgainAfterItWasRestoredKeepsTheFirstRecord() throws IOException {
        givenAModFile("export init() {\n}\n");
        Path backup = backupRoot.resolve(path() + ".grugbak");
        GrugModFileStates.set(modsDir, path(), "deleted");
        Files.write(backup, "the first record".getBytes(StandardCharsets.UTF_8));
        // A later run put the file back by hand, but the record it wrote on the way in is still
        // there, so the second delete must not record the text a second time.
        Files.write(modFile(), "export init() {\n}\n".getBytes(StandardCharsets.UTF_8));

        GrugModFileStates.set(modsDir, path(), "deleted");

        assertFalse(Files.exists(modFile()));
        assertEquals(
                "the first record", new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
    }

    @Test
    void settingTheSameStateTwiceKeepsTheFirstRecord() throws IOException {
        givenAModFile("export init() {\n}\n");
        Path backup = backupRoot.resolve(path() + ".grugbak");

        GrugModFileStates.set(modsDir, path(), "invalid");
        Files.write(backup, "the first record".getBytes(StandardCharsets.UTF_8));
        GrugModFileStates.set(modsDir, path(), "invalid");

        // Recording the marked-up text here is what let the marker outlive every later run.
        assertEquals(
                "the first record", new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
    }

    @Test
    void normalRestoresTheTextAndDropsTheRecord() throws IOException {
        givenAModFile("export init() {\n}\n");
        GrugModFileStates.set(modsDir, path(), "invalid");

        GrugModFileStates.set(modsDir, path(), "normal");

        assertEquals("export init() {\n}\n", modFileText());
        // The record directory and the mod directories under it go too, so a run that put every
        // file back leaves nothing beside the mods at all.
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void restoringOneFileLeavesTheRecordsOfTheOthersAlone() throws IOException {
        givenAModFile("export init() {\n}\n");
        Path code = Files.createDirectories(modsDir.toPath().resolve("other/code"));
        Files.write(code.resolve("a-Item.grug"), "a\n".getBytes(StandardCharsets.UTF_8));
        GrugModFileStates.set(modsDir, path(), "invalid");
        GrugModFileStates.set(modsDir, "other/code/a-Item.grug", "invalid");

        GrugModFileStates.set(modsDir, path(), "normal");

        assertFalse(Files.exists(backupRoot.resolve(path() + ".grugbak")));
        assertTrue(Files.isRegularFile(backupRoot.resolve("other/code/a-Item.grug.grugbak")));
    }

    @Test
    void normalOnAFileThatWasNeverDisturbedDoesNothing() throws IOException {
        givenAModFile("export init() {\n}\n");

        GrugModFileStates.set(modsDir, path(), "normal");

        assertEquals("export init() {\n}\n", modFileText());
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void anUnknownStateIsRejectedWithoutTouchingAnything() throws IOException {
        givenAModFile("export init() {\n}\n");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugModFileStates.set(modsDir, path(), "not_a_state"));

        assertTrue(
                error.getMessage().contains("Expected \"normal\", \"invalid\""),
                error.getMessage());
        assertEquals("export init() {\n}\n", modFileText());
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void settingAMissingFileFails() throws IOException {
        givenAModFile("export init() {\n}\n");

        assertThrows(
                IOException.class,
                () -> GrugModFileStates.set(modsDir, "mymod/code/nope-Block.grug", "invalid"));
    }

    @Test
    void aRunKilledWhileMarkingAFileIsRepairedOnTheNextStartup() throws IOException {
        givenAModFile("export init() {\n}\n");
        // What a run that died between the two calls leaves behind: the marker on the file, and the
        // record it made on the way in.
        GrugModFileStates.set(modsDir, path(), "invalid");
        List<String> log = new ArrayList<>();

        GrugModFileStates.restoreInterruptedRuns(modsDir, log::add);

        assertEquals("export init() {\n}\n", modFileText());
        assertEquals(1, log.size(), log.toString());
        assertTrue(log.get(0).contains(path()), log.get(0));
        // Gone entirely, so the next startup has nothing left to do.
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void aRecordRepairsAFileThatNoLongerExistsAtAll() throws IOException {
        givenAModFile("export init() {\n}\n");
        GrugModFileStates.set(modsDir, path(), "invalid");
        Files.delete(modFile());
        List<String> log = new ArrayList<>();

        GrugModFileStates.restoreInterruptedRuns(modsDir, log::add);

        assertEquals("export init() {\n}\n", modFileText());
        assertEquals(1, log.size(), log.toString());
    }

    @Test
    void aFileAnAuthorAlreadyRevertedIsLeftUntouchedAndUnreported() throws IOException {
        givenAModFile("export init() {\n}\n");
        GrugModFileStates.set(modsDir, path(), "invalid");
        Files.write(modFile(), "export init() {\n}\n".getBytes(StandardCharsets.UTF_8));
        List<String> log = new ArrayList<>();

        GrugModFileStates.restoreInterruptedRuns(modsDir, log::add);

        assertEquals("export init() {\n}\n", modFileText());
        assertTrue(log.isEmpty(), log.toString());
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void severalRecordsAreRestoredInAStableOrder() throws IOException {
        givenAModFile("export init() {\n}\n");
        Path code = Files.createDirectories(modsDir.toPath().resolve("other/code"));
        Files.write(code.resolve("a-Item.grug"), "a\n".getBytes(StandardCharsets.UTF_8));
        Files.write(code.resolve("b-Item.grug"), "b\n".getBytes(StandardCharsets.UTF_8));
        GrugModFileStates.set(modsDir, path(), "invalid");
        GrugModFileStates.set(modsDir, "other/code/a-Item.grug", "invalid");
        GrugModFileStates.set(modsDir, "other/code/b-Item.grug", "no_trailing_newline");
        List<String> log = new ArrayList<>();

        GrugModFileStates.restoreInterruptedRuns(modsDir, log::add);

        assertEquals(3, log.size(), log.toString());
        // In path order, so a run that restores several files names them in the same order every
        // time and its log can be read against a diff.
        assertTrue(log.get(0).contains("mymod/code/thing-Block.grug"), log.get(0));
        assertTrue(log.get(1).contains("other/code/a-Item.grug"), log.get(1));
        assertTrue(log.get(2).contains("other/code/b-Item.grug"), log.get(2));
        assertEquals("export init() {\n}\n", modFileText());
        assertEquals(
                "a\n",
                new String(
                        Files.readAllBytes(code.resolve("a-Item.grug")), StandardCharsets.UTF_8));
        assertEquals(
                "b\n",
                new String(
                        Files.readAllBytes(code.resolve("b-Item.grug")), StandardCharsets.UTF_8));
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void aRecordStillBeingWrittenWhenItsRunDiedIsDiscarded() throws IOException {
        givenAModFile("export init() {\n}\n");
        // A run killed partway through writing the record: the pending file is a prefix of the
        // text, and the file it was recording was never touched.
        Path pending = backupRoot.resolve(path() + ".grugbak.pending");
        Files.createDirectories(pending.getParent());
        Files.write(pending, "export".getBytes(StandardCharsets.UTF_8));
        List<String> log = new ArrayList<>();

        GrugModFileStates.restoreInterruptedRuns(modsDir, log::add);

        assertEquals("export init() {\n}\n", modFileText());
        assertTrue(log.isEmpty(), log.toString());
        assertFalse(Files.exists(backupRoot));
    }

    @Test
    void theRecordLandsBesideTheModsTreeWhicheverWayItIsSpelled() throws IOException {
        givenAModFile("export init() {\n}\n");
        // The Forge loaders reach the repository's mods directory through "../../../mods", so the
        // records have to land in the same place however the path spells it.
        File roundTripped = tmp.resolve("loaders/loader/run/../../../mods").toFile();

        GrugModFileStates.set(roundTripped, path(), "invalid");

        assertTrue(
                Files.isRegularFile(backupRoot.resolve(path() + ".grugbak")),
                "the record did not land beside the mods tree");
        List<String> log = new ArrayList<>();
        GrugModFileStates.restoreInterruptedRuns(roundTripped, log::add);

        assertEquals("export init() {\n}\n", modFileText());
        assertEquals(1, log.size(), log.toString());
    }

    @Test
    void aModsDirectoryWithNoParentKeepsItsRecordsBesideIt() throws IOException {
        // Only a filesystem root has no parent, and a mods directory there is nonsense, but the
        // record directory still needs somewhere to go rather than a NullPointerException at
        // startup.
        Path record = GrugModFileStates.backupRootOf(Paths.get("/"));

        assertEquals(Paths.get("/.grug_test_backups"), record);
    }

    @Test
    void aCleanTreeHasNothingToRepair() throws IOException {
        givenAModFile("export init() {\n}\n");
        List<String> log = new ArrayList<>();

        GrugModFileStates.restoreInterruptedRuns(modsDir, log::add);

        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void aRecordThatCannotBeReadFailsTheStartup() throws IOException {
        givenAModFile("export init() {\n}\n");
        GrugModFileStates.set(modsDir, path(), "invalid");
        // A directory where the mod file belongs: the record reads fine, and writing it back does
        // not, which is the shape any failure to repair takes.
        Files.delete(modFile());
        Files.createDirectories(modFile());

        // Failing loudly beats carrying on to compile the disturbed file and blaming the compiler
        // for a stray character in it.
        assertThrows(
                IOException.class,
                () -> GrugModFileStates.restoreInterruptedRuns(modsDir, message -> {}));
    }
}
