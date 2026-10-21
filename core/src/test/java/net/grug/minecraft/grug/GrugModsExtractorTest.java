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
import java.util.List;
import java.util.Map;

/** Covers installing a mod bundled in the jar. */
class GrugModsExtractorTest {

    @TempDir Path tmp;

    /** A jar holding the mods a and b under mods/, with the index the build writes. */
    private static Map<String, String> jar() {
        Map<String, String> jar = new HashMap<>();
        jar.put(GrugModsExtractor.INDEX, "a/x.txt\na/sub/y.txt\nb/z.txt\n");
        jar.put("/mods/a/x.txt", "x");
        jar.put("/mods/a/sub/y.txt", "y");
        jar.put("/mods/b/z.txt", "z");
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
    void groupsTheIndexByMod() throws IOException {
        assertEquals(
                Map.of("a", List.of("a/x.txt", "a/sub/y.txt"), "b", List.of("b/z.txt")),
                GrugModsExtractor.bundled(resources(jar())));
    }

    @Test
    void installsOneModAndNoOther() throws IOException {
        Path target = target();

        GrugModsExtractor.install(resources(jar()), "a", target);

        assertEquals("x", Files.readString(target.resolve("a/x.txt")));
        assertEquals("y", Files.readString(target.resolve("a/sub/y.txt")));
        assertTrue(Files.notExists(target.resolve("b")));
        assertTrue(Files.notExists(target.resolve(".a.installing")));
    }

    @Test
    void readsTheModsBundledOnTheClasspath() throws IOException {
        // The build writes the index into core's own resources, so this installs a real mod.
        Path target = target();

        GrugModsExtractor.install(GrugModsExtractor.classpath(), "examplemod", target);

        assertTrue(Files.isRegularFile(target.resolve("examplemod/LICENSE")));
        assertTrue(
                GrugModsExtractor.bundled(GrugModsExtractor.classpath()).containsKey("buildcraft"));
    }

    @Test
    void refusesAModTheJarDoesNotBundle() {
        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugModsExtractor.install(resources(jar()), "c", tmp));

        assertTrue(error.getMessage().contains("not a mod bundled"), error.getMessage());
    }

    @Test
    void failsWhenTheIndexIsMissing() {
        Map<String, String> jar = jar();
        jar.remove(GrugModsExtractor.INDEX);

        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugModsExtractor.install(resources(jar), "a", tmp));

        assertTrue(error.getMessage().contains("is missing from the grug jar"), error.getMessage());
    }

    @Test
    void leavesNoHalfInstalledModWhenAFileIsMissing() throws IOException {
        Map<String, String> jar = jar();
        jar.remove("/mods/a/sub/y.txt");
        Path target = target();

        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugModsExtractor.install(resources(jar), "a", target));

        assertTrue(error.getMessage().contains("sub/y.txt"), error.getMessage());
        assertTrue(Files.notExists(target.resolve("a")), "a half-copied mod must not look whole");
    }

    @Test
    void clearsAnInstallThatAnEarlierRunLeftHalfDone() throws IOException {
        Path target = target();
        Files.createDirectories(target.resolve(".a.installing/stale"));
        Files.writeString(target.resolve(".a.installing/stale/old.txt"), "old");

        GrugModsExtractor.install(resources(jar()), "a", target);

        assertTrue(Files.notExists(target.resolve("a/stale")));
        assertTrue(Files.isRegularFile(target.resolve("a/x.txt")));
    }
}
