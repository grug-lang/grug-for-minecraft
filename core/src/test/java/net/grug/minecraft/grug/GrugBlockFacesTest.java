package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Covers finding a block's face textures by reading its model JSON from the mods directory. */
class GrugBlockFacesTest {

    @TempDir Path mods;

    /**
     * Drains the defects the resolver reports, so one test's report cannot be read as another's.
     */
    @BeforeEach
    @AfterEach
    void drainQueue() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
    }

    /** Writes the grug block model {@code name} into the test mod. */
    private void model(String name, String json) throws IOException {
        Path file = mods.resolve("testmod/assets/grug/models/block/" + name + ".json");
        Files.createDirectories(file.getParent());
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private String[] resolve(String blockName) {
        return GrugBlockFaces.resolve(mods.toFile(), blockName);
    }

    private static List<String> reported() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            return new ArrayList<>(GrugErrors.runtimeErrorQueue);
        }
    }

    @Test
    void aCubeAllModelGivesEveryFaceItsTexture() throws IOException {
        model(
                "block",
                "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\":"
                        + " \"grug:block/a\"}}");

        assertArrayEquals(
                new String[] {
                    "grug:block/a", "grug:block/a", "grug:block/a",
                    "grug:block/a", "grug:block/a", "grug:block/a"
                },
                resolve("block"));
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void aVanillaParentWithoutNamespaceIsRecognised() throws IOException {
        model(
                "block",
                "{\"parent\": \"block/cube_top\", \"textures\": {\"top\": \"grug:block/t\","
                        + " \"side\": \"grug:block/s\"}}");

        assertArrayEquals(
                new String[] {
                    "grug:block/s", "grug:block/t", "grug:block/s",
                    "grug:block/s", "grug:block/s", "grug:block/s"
                },
                resolve("block"));
    }

    @Test
    void aGrugParentSuppliesTheTexturesTheChildLeavesOut() throws IOException {
        model(
                "block",
                "{\"parent\": \"grug:block/parent\", \"textures\": {\"top\":"
                        + " \"grug:block/child_top\"}}");
        model(
                "parent",
                "{\"parent\": \"minecraft:block/cube_bottom_top\", \"textures\": {\"bottom\":"
                        + " \"grug:block/b\", \"top\": \"grug:block/p_top\", \"side\":"
                        + " \"grug:block/s\"}}");

        // The child's own top wins over the parent's, and the parent supplies the rest.
        assertArrayEquals(
                new String[] {
                    "grug:block/b", "grug:block/child_top", "grug:block/s",
                    "grug:block/s", "grug:block/s", "grug:block/s"
                },
                resolve("block"));
    }

    @Test
    void aModelWithoutAParentHasNoFaces() throws IOException {
        model("block", "{\"textures\": {\"all\": \"grug:block/a\"}}");

        assertNull(resolve("block"));
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void aBlockWithNoModelHasNoFaces() {
        assertNull(resolve("absent"));
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void aModelParentedOnAVanillaModelThatIsNotAFaceParentHasNoFaces() throws IOException {
        // redstone_engine's parent is the vanilla block model, which no mod ships, so it has no
        // faces
        // and the caller falls back to the by-name texture.
        model(
                "block",
                "{\"parent\": \"minecraft:block/block\", \"textures\": {\"base\":"
                        + " \"grug:block/a\"}}");

        assertNull(resolve("block"));
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void aParentWithoutANamespaceThatNoModShipsHasNoFaces() throws IOException {
        model("block", "{\"parent\": \"block/nothing_here\"}");

        assertNull(resolve("block"));
    }

    @Test
    void aParentThatIsNotAStringIsReported() throws IOException {
        model("block", "{\"parent\": 3}");

        assertNull(resolve("block"));
        List<String> reported = reported();
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.get(0).contains("'parent' that is not a string"), reported.get(0));
    }

    @Test
    void aTexturesValueThatIsNotAnObjectIsIgnored() throws IOException {
        model("block", "{\"parent\": \"minecraft:block/cube_all\", \"textures\": 3}");

        // The non-object is skipped, so the key the parent needs is left undefined and reported.
        assertNull(resolve("block"));
        List<String> reported = reported();
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.get(0).contains("'all'"), reported.get(0));
    }

    @Test
    void aTextureValueThatIsNotAStringIsReported() throws IOException {
        model("block", "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\": 4}}");

        assertNull(resolve("block"));
        List<String> reported = reported();
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.get(0).contains("'all'"), reported.get(0));
    }

    @Test
    void aFaceKeyTheModelDoesNotDefineIsReported() throws IOException {
        model(
                "block",
                "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"other\":"
                        + " \"grug:block/a\"}}");

        assertNull(resolve("block"));
        List<String> reported = reported();
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.get(0).contains("'all'"), reported.get(0));
    }

    @Test
    void aParentChainLongerThanTheLimitHasNoFaces() throws IOException {
        // Two grug models that name each other: the walk gives up at its depth limit.
        model("block", "{\"parent\": \"grug:block/loop_a\"}");
        model("loop_a", "{\"parent\": \"grug:block/loop_b\"}");
        model("loop_b", "{\"parent\": \"grug:block/loop_a\"}");

        assertNull(resolve("block"));
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void aModelThatCannotBeReadStopsTheRun() throws IOException {
        model("block", "[1]");

        assertThrows(RuntimeException.class, () -> resolve("block"));
    }
}
