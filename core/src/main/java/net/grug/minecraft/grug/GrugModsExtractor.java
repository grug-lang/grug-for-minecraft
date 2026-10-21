package net.grug.minecraft.grug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Installs the mods bundled in the jar into a game's mods directory, one mod at a time.
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

    /** The bundled mods in the order of the index, each with the files it holds. */
    public static Map<String, List<String>> bundled(Resources resources) throws IOException {
        Map<String, List<String>> mods = new LinkedHashMap<>();
        for (String relative : index(resources)) {
            int slash = relative.indexOf('/');
            if (slash < 1) {
                throw new IOException(INDEX + " lists " + relative + ", which is not inside a mod");
            }
            String mod = relative.substring(0, slash);
            mods.computeIfAbsent(mod, name -> new ArrayList<>()).add(relative);
        }
        return mods;
    }

    /**
     * Copies one bundled mod into {@code target}, which then holds a directory of the mod's name.
     *
     * <p>The mod is copied beside its final place and renamed once every file is in, so a missing
     * or uncopyable file aborts the install without leaving a half-installed mod that the next run
     * would take for a complete one.
     */
    public static void install(Resources resources, String mod, Path target) throws IOException {
        List<String> files = bundled(resources).get(mod);
        if (files == null) {
            throw new IOException(mod + " is not a mod bundled in the grug jar");
        }
        Path staging = target.resolve("." + mod + ".installing");
        deleteTree(staging);
        for (String relative : files) {
            Path destination = staging.resolve(relative.substring(mod.length() + 1));
            try (InputStream in = resources.open(MODS_ROOT + relative)) {
                if (in == null) {
                    throw new IOException("the index lists it, but the jar does not contain it");
                }
                Files.createDirectories(destination.getParent());
                Files.copy(in, destination);
            } catch (IOException e) {
                throw new IOException("Failed to install file of bundled grug mod: " + relative, e);
            }
        }
        Files.move(staging, target.resolve(mod));
    }

    /** Removes a directory tree if there is one. */
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
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
            // The build writes one path per line and no blank ones.
            while ((line = reader.readLine()) != null) {
                paths.add(line);
            }
        }
        return paths;
    }
}
