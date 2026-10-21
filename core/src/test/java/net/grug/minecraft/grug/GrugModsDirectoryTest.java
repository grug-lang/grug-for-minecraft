package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Covers where an installed game and a development run load grug mods from. */
class GrugModsDirectoryTest {

    @TempDir Path tmp;

    /**
     * A game directory laid out like a loader's run/ in this repository, so the development path
     * {@code ../../../mods} exists next to it. An installed game must not read it even so.
     */
    private File gameDir() throws IOException {
        Path game = Files.createDirectories(tmp.resolve("repo/loaders/some-loader/run"));
        Files.createDirectories(tmp.resolve("repo/mods"));
        return game.toFile();
    }

    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty(GrugReference.MODS_DIR_PROPERTY);
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal a test provoked, cleared so it cannot outlive it.
        }
    }

    @Test
    void anInstalledGameIgnoresTheDevelopmentLayout() throws IOException {
        File game = gameDir();

        assertEquals(new File(game, "grug_mods"), GrugModsDirectory.resolve(game, false));
    }

    @Test
    void aDevelopmentRunReadsTheRepositoryMods() throws IOException {
        File game = gameDir();

        assertEquals(
                new File(game, "../../../mods").getCanonicalFile(),
                GrugModsDirectory.resolve(game, true).getCanonicalFile());
    }

    @Test
    void aDevelopmentRunOutsideTheRepositoryUsesTheGameDirectory() throws IOException {
        File game = Files.createDirectories(tmp.resolve("elsewhere")).toFile();

        assertEquals(new File(game, "grug_mods"), GrugModsDirectory.resolve(game, true));
    }

    @Test
    void theOverrideBeatsBothLayouts() throws IOException {
        File override = Files.createDirectories(tmp.resolve("override")).toFile();
        System.setProperty(GrugReference.MODS_DIR_PROPERTY, override.toString());

        assertEquals(override, GrugModsDirectory.resolve(gameDir(), true));
    }

    @Test
    void anOverrideThatIsNotADirectoryIsFatal() throws IOException {
        System.setProperty(GrugReference.MODS_DIR_PROPERTY, tmp.resolve("missing").toString());
        File game = gameDir();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> GrugModsDirectory.resolve(game, false));

        assertTrue(error.getMessage().contains("GRUG_MODS_DIR"), error.getMessage());
    }

    @Test
    void anInstalledGameGetsTheBundledModsAndModApiJson() throws IOException {
        File game = gameDir();

        File active = GrugModsDirectory.prepare(game, false);

        assertEquals(new File(game, "grug_mods"), active);
        assertTrue(GrugModsDirectory.modApiJson(game).isFile());
        assertTrue(new File(active, "examplemod/LICENSE").isFile());
        assertTrue(new File(active, ".examples_generated.txt").isFile());
    }

    @Test
    void aRunPointedElsewhereGetsNoExamples() throws IOException {
        File override = Files.createDirectories(tmp.resolve("override")).toFile();
        System.setProperty(GrugReference.MODS_DIR_PROPERTY, override.toString());
        File game = gameDir();

        GrugModsDirectory.prepare(game, false);

        assertTrue(GrugModsDirectory.modApiJson(game).isFile());
        assertTrue(Files.notExists(game.toPath().resolve("grug_mods/examplemod")));
        assertEquals(0, override.list().length);
    }

    @Test
    void aGameDirectoryThatCannotHoldGrugModsIsFatal() throws IOException {
        File game = gameDir();
        Files.writeString(game.toPath().resolve("grug_mods"), "a file, not a directory");

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> GrugModsDirectory.prepare(game, false));

        assertTrue(error.getMessage().contains("Failed to create"), error.getMessage());
    }

    @Test
    void aJarWithoutModApiJsonIsFatal() throws IOException {
        File game = gameDir();

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugModsDirectory.prepare(game, false, name -> null));

        assertTrue(error.getMessage().contains("/mod_api.json is missing"), error.getMessage());
    }

    @Test
    void aModApiJsonThatCannotBeWrittenIsFatal() throws IOException {
        File game = gameDir();
        Files.createDirectories(game.toPath().resolve("grug_mods/mod_api.json/in-the-way"));

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> GrugModsDirectory.prepare(game, false));

        assertTrue(error.getMessage().contains("Failed to copy mod_api.json"), error.getMessage());
    }

    @Test
    void anExtractionThatFailsIsFatal() throws IOException {
        File game = gameDir();
        Files.createDirectories(game.toPath().resolve("grug_mods"));
        Files.writeString(game.toPath().resolve("grug_mods/examplemod"), "a file, not a directory");

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> GrugModsDirectory.prepare(game, false));

        assertTrue(
                error.getMessage().contains("Failed to extract the default grug mods"),
                error.getMessage());
    }
}
