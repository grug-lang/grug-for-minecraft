package net.grug.minecraft.grug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies the example mods bundled in the jar into a game's mods directory the first time it runs.
 *
 * <p>The mods are read as classpath resources through the index the build writes next to them,
 * rather than by walking a directory of the jar. A jar that a mod loader nests inside another one
 * (Fabric's jar-in-jar, or the universal jar's layers) is not a directory any loader-independent
 * API can walk, but every loader can open one of its resources by name.
 */
public final class GrugModsExtractor {
    /**
     * The resource listing every bundled file, one path relative to {@link #MODS_ROOT} per line.
     */
    public static final String INDEX = "/grug_bundled_mods.txt";

    /** The resource directory the bundled mods sit under. */
    public static final String MODS_ROOT = "/mods/";

    private GrugModsExtractor() {}

    /** Opens a bundled resource by its absolute name, or answers null when there is none. */
    public interface Resources {
        InputStream open(String name) throws IOException;
    }

    /** The bundled mods on the classpath that loaded grug. */
    public static Resources classpath() {
        return GrugModsExtractor.class::getResourceAsStream;
    }

    /**
     * Copies every bundled file into {@code target}, skipping files that already exist, then writes
     * the marker so a later run is a no-op.
     *
     * <p>A missing index, or a listed file that is missing or cannot be copied, aborts the
     * extraction rather than being skipped. The marker is written once every file is in place, so
     * carrying on would leave a mods directory that is missing files while claiming to be complete,
     * and no later run would look for them again.
     */
    public static void extract(Resources resources, Path target, Path marker) throws IOException {
        if (Files.exists(marker)) return;

        for (String relative : index(resources)) {
            Path destination = target.resolve(relative);
            if (Files.exists(destination)) continue;

            try (InputStream in = resources.open(MODS_ROOT + relative)) {
                if (in == null) {
                    throw new IOException("the index lists it, but the jar does not contain it");
                }
                Files.createDirectories(destination.getParent());
                Files.copy(in, destination);
            } catch (IOException e) {
                throw new IOException("Failed to extract default grug mod file: " + relative, e);
            }
        }

        Files.write(
                marker,
                "The default examples have already been generated.\n"
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> index(Resources resources) throws IOException {
        List<String> paths = new ArrayList<>();
        try (InputStream in = resources.open(INDEX)) {
            if (in == null) {
                throw new IOException(INDEX + " is missing from the grug jar");
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    paths.add(line);
                }
            }
        }
        return paths;
    }
}
