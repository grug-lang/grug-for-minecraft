package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Covers the first-run extraction of the bundled example mods. */
class GrugModsExtractorTest {

    @TempDir Path tmp;

    private Path source() throws IOException {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("sub"));
        Files.writeString(src.resolve("a.txt"), "a");
        Files.writeString(src.resolve("sub/b.txt"), "b");
        return src;
    }

    @Test
    void copiesFilesAndWritesTheMarker() throws IOException {
        Path src = source();
        Path target = tmp.resolve("target");
        Files.createDirectories(target);
        Path marker = target.resolve(".examples_generated.txt");

        GrugModsExtractor.extract(src, target, marker);

        assertEquals("a", Files.readString(target.resolve("a.txt")));
        assertEquals("b", Files.readString(target.resolve("sub/b.txt")));
        assertTrue(Files.exists(marker));
    }

    @Test
    void isANoOpWhenTheMarkerExists() throws IOException {
        Path src = source();
        Path target = tmp.resolve("target");
        Files.createDirectories(target);
        Path marker = target.resolve(".examples_generated.txt");
        Files.writeString(marker, "already extracted");

        GrugModsExtractor.extract(src, target, marker);

        assertTrue(Files.notExists(target.resolve("a.txt")));
    }

    @Test
    void keepsFilesThatAlreadyExist() throws IOException {
        Path src = source();
        Path target = tmp.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("a.txt"), "existing");
        Path marker = target.resolve(".examples_generated.txt");

        GrugModsExtractor.extract(src, target, marker);

        assertEquals("existing", Files.readString(target.resolve("a.txt")));
    }

    @Test
    void failsRatherThanLeavingTheMarkerOverAPartialCopy() throws IOException {
        // The marker says the examples are in place, so a run that carried on past a file it could
        // not copy would leave a mods directory that is missing files no later run would look for.
        Path src = source();
        Path target = tmp.resolve("target-is-a-file");
        Files.writeString(target, "not a directory");
        Path marker = tmp.resolve("marker.txt");

        IOException error =
                assertThrows(
                        IOException.class, () -> GrugModsExtractor.extract(src, target, marker));

        assertTrue(
                error.getMessage().contains("Failed to extract default grug mod file"),
                error.getMessage());
        assertTrue(Files.notExists(marker), "the marker must not claim the copy finished");
    }
}
