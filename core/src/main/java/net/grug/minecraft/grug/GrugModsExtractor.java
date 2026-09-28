package net.grug.minecraft.grug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies the example mods bundled in the jar into a game's mods directory the first time it runs.
 *
 * <p>Lives in {@code net.grug.*} so Java tests can drive it against scratch directories; the loader
 * only supplies the source, target and marker paths.
 */
public final class GrugModsExtractor {
    private GrugModsExtractor() {}

    /**
     * Copies {@code source} into {@code target}, skipping files that already exist, then writes the
     * marker so a later run is a no-op. A file that cannot be copied is collected in {@code errors}
     * rather than aborting the whole extraction.
     */
    public static void extract(Path source, Path target, Path marker, List<String> errors)
            throws IOException {
        if (Files.exists(marker)) return;

        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(
                    src -> {
                        try {
                            Path relative = source.relativize(src);
                            Path destination = target.resolve(relative.toString());

                            if (Files.isDirectory(src)) {
                                if (!Files.exists(destination)) {
                                    Files.createDirectories(destination);
                                }
                            } else if (!Files.exists(destination)) {
                                Files.copy(src, destination, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            errors.add("Failed to extract default grug mod file: " + src);
                        }
                    });
        }

        Files.writeString(marker, "The default examples have already been generated.\n");
    }
}
