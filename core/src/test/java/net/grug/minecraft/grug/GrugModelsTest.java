package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Covers reading a block model's elements into the faces draw_model draws. */
class GrugModelsTest {

    @TempDir Path mods;

    @AfterEach
    void forgetModels() {
        GrugModels.clearCache();
    }

    /** Writes a model into the test mod, and returns the resource path draw_model would get. */
    private String model(String name, String json) throws IOException {
        Path file = mods.resolve("testmod/assets/grug/models/block/" + name + ".json");
        Files.createDirectories(file.getParent());
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return "testmod/assets/grug/models/block/" + name + ".json";
    }

    /** A model with one element and the given faces object, over a texture called "t". */
    private String oneElement(String name, String element) throws IOException {
        return model(
                name, "{\"textures\": {\"t\": \"grug:block/t\"}, \"elements\": [" + element + "]}");
    }

    private List<GrugQuad> load(String path) throws GrugModels.Defect {
        return GrugModels.load(mods.toFile(), path);
    }

    private String defect(String path) {
        return assertThrows(GrugModels.Defect.class, () -> load(path)).getMessage();
    }

    private static double[] corners(GrugQuad quad) {
        double[] result = new double[12];
        for (int corner = 0; corner < 4; corner++) {
            result[corner * 3] = quad.x(corner);
            result[corner * 3 + 1] = quad.y(corner);
            result[corner * 3 + 2] = quad.z(corner);
        }
        return result;
    }

    private static double[] uvs(GrugQuad quad) {
        double[] result = new double[8];
        for (int corner = 0; corner < 4; corner++) {
            result[corner * 2] = quad.u(corner);
            result[corner * 2 + 1] = quad.v(corner);
        }
        return result;
    }

    @Test
    void anElementBecomesOneQuadPerFaceInTheBakersCornerOrder() throws Exception {
        String path =
                oneElement(
                        "box",
                        "{\"from\": [0, 0, 0], \"to\": [16, 8, 16], \"faces\": {"
                                + "\"down\": {\"texture\": \"#t\"},"
                                + "\"up\": {\"texture\": \"#t\"},"
                                + "\"north\": {\"texture\": \"#t\"},"
                                + "\"south\": {\"texture\": \"#t\"},"
                                + "\"west\": {\"texture\": \"#t\"},"
                                + "\"east\": {\"texture\": \"#t\"}}}");

        List<GrugQuad> quads = load(path);

        assertEquals(6, quads.size());
        assertArrayEquals(
                new double[] {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1}, corners(quads.get(0)), "down");
        assertArrayEquals(
                new double[] {0, 0.5, 0, 0, 0.5, 1, 1, 0.5, 1, 1, 0.5, 0},
                corners(quads.get(1)),
                "up");
        assertArrayEquals(
                new double[] {1, 0.5, 0, 1, 0, 0, 0, 0, 0, 0, 0.5, 0},
                corners(quads.get(2)),
                "north");
        assertArrayEquals(
                new double[] {0, 0.5, 1, 0, 0, 1, 1, 0, 1, 1, 0.5, 1},
                corners(quads.get(3)),
                "south");
        assertArrayEquals(
                new double[] {0, 0.5, 0, 0, 0, 0, 0, 0, 1, 0, 0.5, 1},
                corners(quads.get(4)),
                "west");
        assertArrayEquals(
                new double[] {1, 0.5, 1, 1, 0, 1, 1, 0, 0, 1, 0.5, 0},
                corners(quads.get(5)),
                "east");
        for (int side = 0; side < 6; side++) {
            assertEquals(side, quads.get(side).side());
            assertEquals(-1, quads.get(side).cullSide());
            assertEquals("grug:block/t", quads.get(side).texture());
            assertEquals("assets/grug/textures/block/t.png", quads.get(side).texturePath());
        }

        // With no uv, a face is clipped to the element's own extent, as BlockElement.uvsByFace
        // does:
        // the element is the bottom half, so a side shows the bottom half of the texture.
        assertArrayEquals(new double[] {0, 0.5, 0, 1, 1, 1, 1, 0.5}, uvs(quads.get(2)), "north");
        assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1, 1, 0}, uvs(quads.get(0)), "down");
        assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1, 1, 0}, uvs(quads.get(1)), "up");
        assertArrayEquals(new double[] {0, 0.5, 0, 1, 1, 1, 1, 0.5}, uvs(quads.get(3)), "south");
        assertArrayEquals(new double[] {0, 0.5, 0, 1, 1, 1, 1, 0.5}, uvs(quads.get(4)), "west");
        assertArrayEquals(new double[] {0, 0.5, 0, 1, 1, 1, 1, 0.5}, uvs(quads.get(5)), "east");
    }

    @Test
    void anExplicitUvIsUsedInTheBakersOrderAndCullfaceIsKept() throws Exception {
        String path =
                oneElement(
                        "uv",
                        "{\"from\": [4, 4, 4], \"to\": [12, 16, 12], \"rotation\": {\"angle\": 0},"
                            + " \"faces\": {\"north\": {\"uv\": [0, 4, 2, 10], \"texture\": \"#t\","
                            + " \"cullface\": \"north\", \"rotation\": 0}}}");

        GrugQuad north = load(path).get(0);

        // The first two corners take the first u, and the middle two take the second v.
        assertArrayEquals(
                new double[] {0, 0.25, 0, 0.625, 2 / 16.0, 0.625, 2 / 16.0, 0.25}, uvs(north));
        assertEquals(2, north.cullSide());
    }

    @Test
    void aFaceRotationShiftsWhichCornerTakesWhichTextureCoordinate() throws Exception {
        String face =
                "{\"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\": {\"north\": {\"uv\": [0, 4,"
                        + " 2, 10], \"texture\": \"#t\", \"rotation\": %d}}}";

        double[] turned = uvs(load(oneElement("turn_90", String.format(face, 90))).get(0));
        double[] half = uvs(load(oneElement("turn_180", String.format(face, 180))).get(0));
        double[] back = uvs(load(oneElement("turn_270", String.format(face, 270))).get(0));
        double[] negative = uvs(load(oneElement("turn_minus_90", String.format(face, -90))).get(0));

        // Unrotated the corners are (u1, v1), (u1, v2), (u2, v2), (u2, v1), and each quarter turn
        // moves every corner on to the next one's coordinate.
        assertArrayEquals(
                new double[] {0, 0.625, 2 / 16.0, 0.625, 2 / 16.0, 0.25, 0, 0.25}, turned);
        assertArrayEquals(new double[] {2 / 16.0, 0.625, 2 / 16.0, 0.25, 0, 0.25, 0, 0.625}, half);
        assertArrayEquals(new double[] {2 / 16.0, 0.25, 0, 0.25, 0, 0.625, 2 / 16.0, 0.625}, back);
        assertArrayEquals(back, negative);
    }

    @Test
    void theNearestModelWithElementsDrawsAndTheChildsTexturesWin() throws Exception {
        model(
                "parent",
                "{\"parent\": \"minecraft:block/block\", \"textures\": {\"trunk\":"
                        + " \"grug:block/blue\", \"base\": \"grug:block/base\"}, \"elements\":"
                        + " [{\"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\": {\"up\":"
                        + " {\"texture\": \"#trunk\"}, \"down\": {\"texture\": \"#base\"}}}]}");
        String child =
                model(
                        "child",
                        "{\"parent\": \"grug:block/parent\", \"textures\": {\"trunk\":"
                                + " \"grug:block/red\"}}");

        List<GrugQuad> quads = load(child);

        assertEquals("grug:block/base", quads.get(0).texture());
        assertEquals("grug:block/red", quads.get(1).texture());
    }

    @Test
    void aChildsOwnElementsReplaceItsParents() throws Exception {
        model(
                "elements_parent",
                "{\"textures\": {\"t\": \"grug:block/t\"}, \"elements\": [{\"from\": [0, 0, 0],"
                        + " \"to\": [16, 16, 16], \"faces\": {\"up\": {\"texture\": \"#t\"}}}]}");
        String child =
                model(
                        "elements_child",
                        "{\"parent\": \"grug:block/elements_parent\", \"elements\": [{\"from\": [0,"
                            + " 0, 0], \"to\": [16, 16, 16], \"faces\": {\"down\": {\"texture\":"
                            + " \"block/stone\"}}}]}");

        List<GrugQuad> quads = load(child);

        assertEquals(1, quads.size());
        assertEquals(0, quads.get(0).side());
        assertEquals("minecraft:block/stone", quads.get(0).texture());
    }

    @Test
    void aTextureNamedDirectlyKeepsItsNamespaceAndAParentWithoutOneIsVanilla() throws Exception {
        String path =
                model(
                        "direct",
                        "{\"parent\": \"block/block\", \"elements\": [{\"from\": [0, 0, 0], \"to\":"
                                + " [16, 16, 16], \"faces\": {\"up\": {\"texture\":"
                                + " \"grug:block/x\"}}}]}");

        assertEquals("grug:block/x", load(path).get(0).texture());
    }

    @Test
    void aModelIsFoundByIdAndRelativeOrAbsolutePathsAgree() throws Exception {
        String path =
                oneElement("byid", "{\"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\": {}}");

        assertEquals(0, GrugModels.loadById(mods.toFile(), "grug:block/byid").size());
        assertEquals(0, load(mods.resolve(path).toString()).size());
    }

    @Test
    void aParsedModelIsReusedUntilAFileInItsChainChanges() throws Exception {
        String path =
                oneElement(
                        "cached",
                        "{\"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\": {\"up\":"
                                + " {\"texture\": \"#t\"}}}");
        List<GrugQuad> first = load(path);

        assertSame(first, load(path), "an unchanged model is not read again");

        File file = mods.resolve(path).toFile();
        assertTrue(file.setLastModified(file.lastModified() - 10_000));

        assertNotSame(first, load(path), "a changed model is read again");
    }

    @Test
    void texturePathsAddTheVanillaNamespaceWhenThereIsNone() {
        assertEquals("assets/grug/textures/block/a.png", GrugModels.texturePath("grug:block/a"));
        assertEquals("assets/minecraft/textures/block/a.png", GrugModels.texturePath("block/a"));
    }

    @Test
    void aParentChainThatNeverEndsIsACycle() throws Exception {
        String path = model("loop", "{\"parent\": \"grug:block/loop\"}");

        assertTrue(defect(path).contains("cycle"));
    }

    @Test
    void aModelWithNoElementsAnywhereIsRefused() throws Exception {
        String path = model("empty", "{\"textures\": {}}");

        assertTrue(defect(path).contains("no elements"));
    }

    @Test
    void aMissingParentOrModelIsRefused() throws Exception {
        String path = model("orphan", "{\"parent\": \"grug:block/nowhere\"}");

        assertTrue(defect(path).contains("No mod ships the model grug:block/nowhere"));
        assertTrue(defect("testmod/assets/grug/models/block/absent.json").contains("no model at"));
        assertThrows(
                GrugModels.Defect.class, () -> GrugModels.loadById(mods.toFile(), "block/absent"));
    }

    @Test
    void malformedJsonIsRefused() throws Exception {
        assertTrue(defect(model("array", "[]")).contains("not a JSON object"));
        assertTrue(defect(model("broken", "{")).contains("cannot be read"));
        assertTrue(defect(model("parent_number", "{\"parent\": 3}")).contains("'parent'"));
        assertTrue(defect(model("parent_object", "{\"parent\": {}}")).contains("'parent'"));
        assertTrue(defect(model("textures_array", "{\"textures\": []}")).contains("'textures'"));
        assertTrue(
                defect(model("texture_number", "{\"textures\": {\"t\": 3}}"))
                        .contains("not a string"));
        assertTrue(
                defect(model("texture_object", "{\"textures\": {\"t\": {}}}"))
                        .contains("not a string"));
        assertTrue(defect(model("elements_object", "{\"elements\": {}}")).contains("'elements'"));
        assertTrue(defect(model("element_number", "{\"elements\": [3]}")).contains("element"));
    }

    @Test
    void malformedElementsAreRefused() throws Exception {
        String cube = "\"from\": [0, 0, 0], \"to\": [16, 16, 16]";
        String up = "\"faces\": {\"up\": {\"texture\": \"#t\"}}";

        assertTrue(defect(oneElement("no_from", "{" + up + "}")).contains("'from'"));
        assertTrue(
                defect(oneElement("short_to", "{\"from\": [0, 0, 0], \"to\": [16], " + up + "}"))
                        .contains("'to'"));
        assertTrue(
                defect(oneElement("object_to", "{\"from\": [0, 0, 0], \"to\": {}, " + up + "}"))
                        .contains("'to'"));
        assertTrue(
                defect(
                                oneElement(
                                        "word_from",
                                        "{\"from\": [\"a\", 0, 0], \"to\": [16, 16, 16], "
                                                + up
                                                + "}"))
                        .contains("not a number"));
        assertTrue(
                defect(
                                oneElement(
                                        "array_from",
                                        "{\"from\": [[0], 0, 0], \"to\": [16, 16, 16], "
                                                + up
                                                + "}"))
                        .contains("not a number"));
        assertTrue(
                defect(
                                oneElement(
                                        "rotated",
                                        "{"
                                                + cube
                                                + ", \"rotation\": {\"angle\": 45}, "
                                                + up
                                                + "}"))
                        .contains("45.0 degrees"));
        assertTrue(
                defect(oneElement("rotation_number", "{" + cube + ", \"rotation\": 0, " + up + "}"))
                        .contains("'rotation'"));
        assertTrue(
                defect(
                                oneElement(
                                        "rotation_no_angle",
                                        "{" + cube + ", \"rotation\": {}, " + up + "}"))
                        .contains("rotation angle"));
        assertTrue(defect(oneElement("no_faces", "{" + cube + "}")).contains("'faces'"));
        assertTrue(
                defect(oneElement("faces_array", "{" + cube + ", \"faces\": []}"))
                        .contains("'faces'"));
        assertTrue(
                defect(oneElement("face_number", "{" + cube + ", \"faces\": {\"up\": 3}}"))
                        .contains("'up' face"));
    }

    @Test
    void malformedFacesAreRefused() throws Exception {
        String cube = "\"from\": [0, 0, 0], \"to\": [16, 16, 16]";

        assertTrue(
                defect(oneElement("no_texture", "{" + cube + ", \"faces\": {\"up\": {}}}"))
                        .contains("'texture'"));
        assertTrue(
                defect(
                                oneElement(
                                        "texture_number",
                                        "{" + cube + ", \"faces\": {\"up\": {\"texture\": 3}}}"))
                        .contains("'texture'"));
        assertTrue(
                defect(
                                oneElement(
                                        "texture_object",
                                        "{" + cube + ", \"faces\": {\"up\": {\"texture\": {}}}}"))
                        .contains("'texture'"));
        assertTrue(
                defect(
                                oneElement(
                                        "unresolved",
                                        "{"
                                                + cube
                                                + ", \"faces\": {\"up\": {\"texture\":"
                                                + " \"#nope\"}}}"))
                        .contains("'#nope'"));
        assertTrue(
                defect(
                                oneElement(
                                        "face_rotated",
                                        "{"
                                                + cube
                                                + ", \"faces\": {\"up\": {\"texture\": \"#t\","
                                                + " \"rotation\": 45}}}"))
                        .contains("quarter turns"));
        assertTrue(
                defect(
                                oneElement(
                                        "bad_cullface",
                                        "{"
                                                + cube
                                                + ", \"faces\": {\"up\": {\"texture\": \"#t\","
                                                + " \"cullface\": \"sideways\"}}}"))
                        .contains("cullface"));
        assertTrue(
                defect(
                                oneElement(
                                        "object_cullface",
                                        "{"
                                                + cube
                                                + ", \"faces\": {\"up\": {\"texture\": \"#t\","
                                                + " \"cullface\": {}}}}"))
                        .contains("cullface"));
    }

    @Test
    void aQuadNamesItsTextureAndSide() throws Exception {
        String path =
                oneElement(
                        "named",
                        "{\"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\": {\"down\":"
                                + " {\"texture\": \"#t\"}}}");

        assertEquals("GrugQuad[grug:block/t, side 0]", load(path).get(0).toString());
    }

    @Test
    void aBlocksItemFormIsItsItemModel() throws Exception {
        oneElement(
                "item_block",
                "{\"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\": {\"up\": {\"texture\":"
                        + " \"#t\"}}}");
        Path item = mods.resolve("testmod/assets/grug/models/item/item_block.json");
        Files.createDirectories(item.getParent());
        Files.write(
                item, "{\"parent\": \"grug:block/item_block\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, GrugModels.itemModel(mods.toFile(), "grug:item_block").size());
    }

    @Test
    void anItemFormThatCannotBeDrawnIsReportedOnceAndLeftToTheLoader() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }

        assertEquals(null, GrugModels.itemModel(mods.toFile(), "grug:no_item_model"));
        assertEquals(null, GrugModels.itemModel(mods.toFile(), "grug:no_item_model"));

        synchronized (GrugErrors.runtimeErrorQueue) {
            assertEquals(1, GrugErrors.runtimeErrorQueue.size());
            assertTrue(GrugErrors.runtimeErrorQueue.peek().contains("grug:no_item_model"));
            GrugErrors.runtimeErrorQueue.clear();
        }
    }
}
