package net.grug.minecraft.grug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
     * marker so a later run is a no-op.
     *
     * <p>A file that cannot be copied aborts the extraction rather than being collected and left
     * behind. The marker is written once the walk finishes, so carrying on would leave a mods
     * directory that is missing files while claiming to be complete, and no later run would look for
     * them again.
     */
    public static void extract(Path source, Path target, Path marker) throws IOException {
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
                            throw new UncopyableFile(src, e);
                        }
                    });
        } catch (UncopyableFile e) {
            throw new IOException("Failed to extract default grug mod file: " + e.source, e.getCause());
        }

        Files.write(
                marker,
                "The default examples have already been generated.\n"
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Carries a per-file copy failure out of {@link Stream#forEach}, whose consumer cannot throw a
     * checked exception. The extraction rethrows it as the {@link IOException} it started as.
     */
    private static final class UncopyableFile extends RuntimeException {
        private final Path source;

        UncopyableFile(Path source, IOException cause) {
            super(cause);
            this.source = source;
        }
    }
}
