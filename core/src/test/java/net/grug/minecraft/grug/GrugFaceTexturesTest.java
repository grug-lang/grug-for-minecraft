package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Covers the per-face texture references a block model asks for. */
class GrugFaceTexturesTest {

    /**
     * Drains the defects the validator reports, so one test's report cannot be read as another's.
     */
    @BeforeEach
    @AfterEach
    void drainQueue() {
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.clear();
        }
    }

    private static List<String> reported() {
        synchronized (Grug.runtimeErrorQueue) {
            return new ArrayList<>(Grug.runtimeErrorQueue);
        }
    }

    private static Map<String, String> textures(String... keysAndValues) {
        Map<String, String> textures = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            textures.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return textures;
    }

    @Test
    void mapsEachBuiltInParentToItsFaceKeys() {
        assertEquals(
                List.of("all", "all", "all", "all", "all", "all"),
                List.of(GrugFaceTextures.vanillaParentFaceKeys("block/cube_all")));
        assertEquals(
                List.of("bottom", "top", "side", "side", "side", "side"),
                List.of(GrugFaceTextures.vanillaParentFaceKeys("minecraft:block/cube_bottom_top")));
        assertEquals(
                List.of("end", "end", "side", "side", "side", "side"),
                List.of(GrugFaceTextures.vanillaParentFaceKeys("block/cube_column")));
        assertEquals(
                List.of("side", "top", "side", "side", "side", "side"),
                List.of(GrugFaceTextures.vanillaParentFaceKeys("block/cube_top")));
        assertEquals(
                List.of("down", "up", "north", "south", "west", "east"),
                List.of(GrugFaceTextures.vanillaParentFaceKeys("block/cube")));
    }

    @Test
    void hasNoFaceKeysForAModelThatIsNotBuiltIn() {
        assertNull(GrugFaceTextures.vanillaParentFaceKeys("grug:block/my_block"));
        assertNull(GrugFaceTextures.vanillaParentFaceKeys("block/cube_column_top"));
    }

    @Test
    void givesEachFaceTheTextureItsKeyNames() {
        String[] faces =
                GrugFaceTextures.resolve(
                        "thing",
                        new String[] {"end", "end", "side", "side", "side", "side"},
                        textures(
                                "end", "grug:block/thing_end",
                                "side", "grug:block/thing_side"));

        assertEquals(
                List.of(
                        "grug:block/thing_end",
                        "grug:block/thing_end",
                        "grug:block/thing_side",
                        "grug:block/thing_side",
                        "grug:block/thing_side",
                        "grug:block/thing_side"),
                List.of(faces));
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void handsBackAVanillaReferenceAsWritten() {
        // Whether a vanilla texture can be loaded is the resource loader's question, so the model
        // resolution passes the reference through rather than deciding it here.
        String[] faces =
                GrugFaceTextures.resolve(
                        "thing", new String[] {"all"}, textures("all", "minecraft:block/stone"));

        assertEquals("minecraft:block/stone", faces[0]);
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void readsAReferenceWithNoNamespaceAsAVanillaOne() {
        assertEquals(
                "minecraft:block/stone",
                GrugFaceTextures.followKey(textures("all", "block/stone"), "all"));
    }

    @Test
    void followsAKeyThatNamesAnotherKey() {
        String[] faces =
                GrugFaceTextures.resolve(
                        "thing",
                        new String[] {"all"},
                        textures("all", "#base", "base", "grug:block/thing"));

        assertEquals("grug:block/thing", faces[0]);
    }

    @Test
    void reportsAFaceWhoseKeyIsNotDefined() {
        String[] faces =
                GrugFaceTextures.resolve(
                        "thing", new String[] {"all", "side"}, textures("all", "grug:block/thing"));

        assertNull(faces);
        List<String> reported = reported();
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(
                reported.get(0)
                        .contains(
                                "Block 'thing' uses the texture key 'side', which its model does"
                                        + " not define."),
                reported.get(0));
    }

    @Test
    void reportsAFaceWhoseKeyChainLeadsNowhere() {
        String[] faces =
                GrugFaceTextures.resolve(
                        "thing", new String[] {"all"}, textures("all", "#a", "a", "#b", "b", "#c"));

        assertNull(faces);
        List<String> reported = reported();
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.get(0).contains("'all'"), reported.toString());
        assertTrue(
                reported.get(0).contains("whose '#' chain does not resolve to a texture."),
                reported.toString());
    }

    @Test
    void mergesATexturesObjectWithoutOverwritingTheChild() {
        Map<String, String> into = new HashMap<>();
        into.put("all", "grug:block/child");
        JsonObject textures =
                JsonParser.parseString(
                                "{\"all\": \"grug:block/parent\","
                                        + " \"side\": \"grug:block/side\"}")
                        .getAsJsonObject();

        assertTrue(GrugFaceTextures.mergeTextures("thing", textures, into));

        assertEquals("grug:block/child", into.get("all"));
        assertEquals("grug:block/side", into.get("side"));
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void reportsATextureValueThatIsNotAString() {
        Map<String, String> into = new HashMap<>();
        JsonObject textures =
                JsonParser.parseString("{\"all\": {\"texture\": \"grug:block/thing\"}}")
                        .getAsJsonObject();

        assertFalse(GrugFaceTextures.mergeTextures("thing", textures, into));

        assertTrue(into.isEmpty(), into.toString());
        List<String> reported = reported();
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(
                reported.get(0).contains("Block 'thing' gives the texture key 'all'"),
                reported.get(0));
    }

    @Test
    void reportsATextureValueThatIsANumber() {
        Map<String, String> into = new HashMap<>();
        JsonObject textures = JsonParser.parseString("{\"all\": 5}").getAsJsonObject();

        assertFalse(GrugFaceTextures.mergeTextures("thing", textures, into));

        assertTrue(into.isEmpty(), into.toString());
        assertEquals(1, reported().size(), reported().toString());
    }

    @Test
    void treatsAKeyChainThatRepeatsAsUndefined() {
        Map<String, String> textures = new HashMap<>();
        textures.put("a", "#b");
        textures.put("b", "#a");

        assertNull(GrugFaceTextures.followKey(textures, "a"));
    }

    @Test
    void reportsAKeyThatIsNotDefinedAtAll() {
        assertNull(GrugFaceTextures.followKey(textures("all", "grug:block/thing"), "side"));
    }
}
