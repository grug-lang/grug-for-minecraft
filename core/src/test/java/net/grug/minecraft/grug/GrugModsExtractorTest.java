package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Covers the first-run extraction of the bundled example mods. */
class GrugModsExtractorTest {

    @TempDir Path tmp;

    /** A jar holding a.txt and sub/b.txt under mods/, with the index the build writes. */
    private static Map<String, String> jar() {
        Map<String, String> jar = new HashMap<>();
        jar.put(GrugModsExtractor.INDEX, "a.txt\nsub/b.txt\n");
        jar.put("/mods/a.txt", "a");
        jar.put("/mods/sub/b.txt", "b");
        return jar;
    }

    private static GrugModsExtractor.Resources resources(Map<String, String> jar) {
        return name -> {
            String content = jar.get(name);
            return content == null
                    ? null
                    : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        };
    }

    private Path target() throws IOException {
        return Files.createDirectories(tmp.resolve("target"));
    }

    @Test
    void copiesFilesAndWritesTheMarker() throws IOException {
        Path target = target();
        Path marker = target.resolve(".examples_generated.txt");

        GrugModsExtractor.extract(resources(jar()), target, marker);

        assertEquals("a", Files.readString(target.resolve("a.txt")));
        assertEquals("b", Files.readString(target.resolve("sub/b.txt")));
        assertTrue(Files.exists(marker));
    }

    @Test
    void readsTheModsBundledOnTheClasspath() throws IOException {
        // The build writes the index into core's own resources, so this extracts the real mods.
        Path target = target();

        GrugModsExtractor.extract(
                GrugModsExtractor.classpath(), target, target.resolve(".examples_generated.txt"));

        assertTrue(Files.isRegularFile(target.resolve("coverage/LICENSE")));
    }

    @Test
    void isANoOpWhenTheMarkerExists() throws IOException {
        Path target = target();
        Path marker = target.resolve(".examples_generated.txt");
        Files.writeString(marker, "already extracted");

        GrugModsExtractor.extract(resources(jar()), target, marker);

        assertTrue(Files.notExists(target.resolve("a.txt")));
    }

    @Test
    void keepsFilesThatAlreadyExist() throws IOException {
        Path target = target();
        Files.writeString(target.resolve("a.txt"), "existing");

        GrugModsExtractor.extract(resources(jar()), target, target.resolve("marker.txt"));

        assertEquals("existing", Files.readString(target.resolve("a.txt")));
    }

    @Test
    void failsWhenTheIndexIsMissing() throws IOException {
        Map<String, String> jar = jar();
        jar.remove(GrugModsExtractor.INDEX);
        Path marker = tmp.resolve("marker.txt");

        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugModsExtractor.extract(resources(jar), target(), marker));

        assertTrue(error.getMessage().contains("is missing from the grug jar"), error.getMessage());
        assertTrue(Files.notExists(marker));
    }

    @Test
    void failsWhenAnIndexedFileIsMissing() throws IOException {
        Map<String, String> jar = jar();
        jar.remove("/mods/sub/b.txt");
        Path marker = tmp.resolve("marker.txt");

        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugModsExtractor.extract(resources(jar), target(), marker));

        assertTrue(error.getMessage().contains("sub/b.txt"), error.getMessage());
        assertTrue(Files.notExists(marker));
    }

    @Test
    void failsRatherThanLeavingTheMarkerOverAPartialCopy() throws IOException {
        // The marker says the examples are in place, so a run that carried on past a file it could
        // not copy would leave a mods directory that is missing files no later run would look for.
        Path target = tmp.resolve("target-is-a-file");
        Files.writeString(target, "not a directory");
        Path marker = tmp.resolve("marker.txt");

        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugModsExtractor.extract(resources(jar()), target, marker));

        assertTrue(
                error.getMessage().contains("Failed to extract default grug mod file"),
                error.getMessage());
        assertTrue(Files.notExists(marker), "the marker must not claim the copy finished");
    }
}
