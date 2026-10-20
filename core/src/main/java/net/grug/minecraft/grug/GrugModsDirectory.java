package net.grug.minecraft.grug;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The grug mods directory every loader loads from, shared so that an installed game resolves it the
 * same way on every loader.
 *
 * <p>An installed game keeps its grug mods in {@code <game directory>/grug_mods}, which the first
 * run fills with the bundled example mods. Only a development run, where the game directory is a
 * loader's {@code run/} inside this repository, reads the repository's own {@code mods/} through
 * {@code ../../../mods}, and {@link GrugReference#modsDirOverride()} beats both.
 */
public final class GrugModsDirectory {
    /** The directory under the game directory an installed game keeps its grug mods in. */
    public static final String NAME = "grug_mods";

    /** Where the development layout puts the repository's mods, relative to a loader's run/. */
    static final String DEV_MODS = "../../../mods";

    private GrugModsDirectory() {}

    /**
     * The directory grug loads mods from.
     *
     * @param development whether the loader reports a development environment; an installed game
     *     never does, so it never looks for the repository's layout
     */
    public static File resolve(File gameDir, boolean development) {
        File override = GrugReference.modsDirOverride();
        if (override != null) {
            if (!override.isDirectory()) {
                throw Grug.fatal("GRUG_MODS_DIR is not a directory: " + override);
            }
            return override;
        }

        if (development) {
            File devGrugDir = new File(gameDir, DEV_MODS);
            if (devGrugDir.isDirectory()) {
                return devGrugDir;
            }
        }
        return new File(gameDir, NAME);
    }

    /** The copy of the jar's mod_api.json that {@link #prepare} writes for grug-rs to read. */
    public static File modApiJson(File gameDir) {
        return new File(new File(gameDir, NAME), "mod_api.json");
    }

    /**
     * Readies the game directory for {@code GrugCore.initialize}: writes the jar's mod_api.json to
     * {@link #modApiJson}, and on the first run of an installed game extracts the bundled example
     * mods into it.
     *
     * @return the directory grug loads mods from, as {@link #resolve} answers it
     */
    public static File prepare(File gameDir, boolean development) {
        return prepare(gameDir, development, GrugModsExtractor.classpath());
    }

    static File prepare(File gameDir, boolean development, GrugModsExtractor.Resources jar) {
        File runGrugDir = new File(gameDir, NAME);
        if (!runGrugDir.isDirectory() && !runGrugDir.mkdirs()) {
            throw Grug.fatal("Failed to create " + runGrugDir);
        }

        try (InputStream in = jar.open("/mod_api.json")) {
            if (in == null) {
                throw Grug.fatal("/mod_api.json is missing from the grug jar");
            }
            Files.copy(in, modApiJson(gameDir).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw Grug.fatal("Failed to copy mod_api.json", e);
        }

        File active = resolve(gameDir, development);
        try {
            // A run pointed at another directory, a reference run's or the repository's, loads
            // exactly what is there, so the examples only ever land in the game's own directory.
            if (active.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
                GrugModsExtractor.extract(
                        jar,
                        runGrugDir.toPath(),
                        runGrugDir.toPath().resolve(".examples_generated.txt"));
            }
        } catch (IOException e) {
            throw Grug.fatal("Failed to extract the default grug mods", e);
        }
        return active;
    }
}
