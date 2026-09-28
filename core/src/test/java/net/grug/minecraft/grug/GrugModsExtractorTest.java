package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        List<String> errors = new ArrayList<>();

        GrugModsExtractor.extract(src, target, marker, errors);

        assertEquals("a", Files.readString(target.resolve("a.txt")));
        assertEquals("b", Files.readString(target.resolve("sub/b.txt")));
        assertTrue(Files.exists(marker));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void isANoOpWhenTheMarkerExists() throws IOException {
        Path src = source();
        Path target = tmp.resolve("target");
        Files.createDirectories(target);
        Path marker = target.resolve(".examples_generated.txt");
        Files.writeString(marker, "already extracted");

        GrugModsExtractor.extract(src, target, marker, new ArrayList<>());

        assertTrue(Files.notExists(target.resolve("a.txt")));
    }

    @Test
    void keepsFilesThatAlreadyExist() throws IOException {
        Path src = source();
        Path target = tmp.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("a.txt"), "existing");
        Path marker = target.resolve(".examples_generated.txt");

        GrugModsExtractor.extract(src, target, marker, new ArrayList<>());

        assertEquals("existing", Files.readString(target.resolve("a.txt")));
    }

    @Test
    void collectsAnErrorWhenAFileCannotBeCopied() throws IOException {
        Path src = source();
        Path target = tmp.resolve("target-is-a-file");
        Files.writeString(target, "not a directory");
        List<String> errors = new ArrayList<>();

        GrugModsExtractor.extract(src, target, tmp.resolve("marker.txt"), errors);

        assertFalse(errors.isEmpty(), "a failed copy should be reported");
    }
}
