package net.grug.minecraft.grug;

import java.util.Map;

/**
 * The per-face texture references a block model asks for, which is pure logic about the model's own
 * {@code textures} map.
 *
 * <p>Lives in {@code net.grug.*} so Java tests can drive it against a scratch map, rather than in a
 * loader's {@code GrugBlockModels}, which can only reach a model by reading the mods directory out
 * of a running game.
 *
 * <p>A face whose key names nothing is a mod-tree defect. The model asked for a texture and did not
 * say which, so the face would render whatever the atlas happened to hold, which is a picture
 * nobody chose. It is reported and the block falls back to its single texture, which is a real
 * rendering rather than a broken one.
 */
public final class GrugFaceTextures {

    /** How far a chain of {@code "#key"} indirections may go before it counts as a cycle. */
    public static final int MAX_DEPTH = 8;

    @GrugGenerated("utility class: never instantiated")
    private GrugFaceTextures() {}

    /**
     * The face keys a built-in model parent supplies, or null for a parent that is another model.
     *
     * <p>The keys are the ones vanilla's own block models use, so a grug model may parent one of
     * them and fill in only the faces that differ. Indices match {@code Block.getSprite(int side)}:
     * 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
     */
    public static String[] vanillaParentFaceKeys(String parent) {
        String name =
                parent.startsWith("minecraft:") ? parent.substring("minecraft:".length()) : parent;
        // A switch rather than a lookup map because the values are built inline, and because core
        // compiles to Java 8.
        switch (name) {
            case "block/cube_all":
                return new String[] {"all", "all", "all", "all", "all", "all"};
            case "block/cube_bottom_top":
                return new String[] {"bottom", "top", "side", "side", "side", "side"};
            case "block/cube_column":
                return new String[] {"end", "end", "side", "side", "side", "side"};
            case "block/cube_top":
                return new String[] {"side", "top", "side", "side", "side", "side"};
            case "block/cube":
                return new String[] {"down", "up", "north", "south", "west", "east"};
            default:
                return null;
        }
    }

    /**
     * The texture reference each face gets, or null once the model's own defect has been reported.
     *
     * <p>A reference is handed back as written, namespace and all, because whether the texture can
     * be loaded is a question for the resource loader: {@code grug:block/foo} is a file a mod ships
     * and {@code minecraft:block/stone} is one it does not, and only the loader can tell them
     * apart.
     */
    public static String[] resolve(
            String blockName, String[] faceKeys, Map<String, String> textures) {
        String[] result = new String[faceKeys.length];
        for (int face = 0; face < faceKeys.length; face++) {
            String ref = followKey(textures, faceKeys[face]);
            if (ref == null) {
                GrugModTreeDefect.report(
                        "Block '"
                                + blockName
                                + "' uses the texture key '"
                                + faceKeys[face]
                                + "', which its model does not define.");
                return null;
            }
            result[face] = ref;
        }
        return result;
    }

    /**
     * The reference {@code key} names, or null when nothing in the map defines it.
     *
     * <p>A value may point at another key with a leading {@code #}, as many model chains do. A
     * chain that never lands is a cycle, which ends the walk like a key that was never there.
     */
    static String followKey(Map<String, String> textures, String key) {
        String value = textures.get(key);
        for (int i = 0; i < MAX_DEPTH && value != null && value.startsWith("#"); i++) {
            value = textures.get(value.substring(1));
        }
        if (value == null || value.startsWith("#")) {
            return null;
        }
        // A reference without a namespace is a vanilla one, which is how the game reads it too.
        return value.indexOf(':') < 0 ? "minecraft:" + value : value;
    }
}
