package net.grug.minecraft.ornithe.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.grug.minecraft.ornithe.GrugModLoader;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves which texture each face of a grug block uses by reading its model
 * JSON (the same file the Forge and StationAPI loaders use) straight from the
 * grug mods directory.
 *
 * Face indices match Block.getSprite(int side): 0 down, 1 up, 2 north, 3 south,
 * 4 west, 5 east.
 */
public final class GrugBlockModels {
    private static final int MAX_DEPTH = 8;

    private GrugBlockModels() {
    }

    /**
     * @return 6 texture references ("namespace:path", indexed by face), or null if
     *         the block has no usable model, in which case the caller should fall
     *         back to a single texture.
     */
    public static String[] resolveFaceTextures(String blockName) {
        Map<String, String> textures = new HashMap<>();
        String current = "grug:block/" + blockName;

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            JsonObject model = readModel(current);
            if (model == null) {
                return null;
            }

            // Children are visited first, so putIfAbsent lets them override parents
            JsonElement texturesElement = model.get("textures");
            if (texturesElement != null && texturesElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : texturesElement.getAsJsonObject().entrySet()) {
                    textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }

            JsonElement parentElement = model.get("parent");
            if (parentElement == null) {
                return null;
            }
            String parent = parentElement.getAsString();

            String[] faceKeys = faceKeysForVanillaParent(parent);
            if (faceKeys != null) {
                return resolveKeys(blockName, faceKeys, textures);
            }

            // Not a built-in parent, so it has to be another grug model
            current = parent;
        }

        return null;
    }

    /** Converts "grug:block/foo" to "assets/grug/textures/block/foo.png". */
    public static String toTexturePath(String textureRef) {
        int colon = textureRef.indexOf(':');
        String namespace = textureRef.substring(0, colon);
        String path = textureRef.substring(colon + 1);
        return "assets/" + namespace + "/textures/" + path + ".png";
    }

    private static String[] faceKeysForVanillaParent(String parent) {
        if (parent.startsWith("minecraft:")) {
            parent = parent.substring("minecraft:".length());
        }
        return switch (parent) {
            case "block/cube_all" -> new String[] { "all", "all", "all", "all", "all", "all" };
            case "block/cube_bottom_top" -> new String[] { "bottom", "top", "side", "side", "side", "side" };
            case "block/cube_column" -> new String[] { "end", "end", "side", "side", "side", "side" };
            case "block/cube_top" -> new String[] { "side", "top", "side", "side", "side", "side" };
            case "block/cube" -> new String[] { "down", "up", "north", "south", "west", "east" };
            default -> null;
        };
    }

    private static String[] resolveKeys(String blockName, String[] faceKeys, Map<String, String> textures) {
        String[] result = new String[faceKeys.length];
        for (int face = 0; face < faceKeys.length; face++) {
            String ref = lookup(textures, faceKeys[face]);
            if (ref == null || ref.startsWith("minecraft:")) {
                GrugModLoader.LOGGER.warn("Block '" + blockName + "': texture key '" + faceKeys[face]
                        + "' is missing or not a grug texture, falling back to a single texture");
                return null;
            }
            result[face] = ref;
        }
        GrugModLoader.LOGGER.info("Block '" + blockName + "' face textures (down, up, north, south, west, east): "
                + Arrays.toString(result));
        return result;
    }

    /** Follows "#key" indirections and returns a "namespace:path" reference, or null. */
    private static String lookup(Map<String, String> textures, String key) {
        String value = textures.get(key);
        for (int i = 0; i < MAX_DEPTH && value != null && value.startsWith("#"); i++) {
            value = textures.get(value.substring(1));
        }
        if (value == null || value.startsWith("#")) {
            return null;
        }
        return value.indexOf(':') < 0 ? "minecraft:" + value : value;
    }

    private static JsonObject readModel(String modelId) {
        int colon = modelId.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : modelId.substring(0, colon);
        String path = colon < 0 ? modelId : modelId.substring(colon + 1);

        File[] modDirs = GrugModLoader.getActiveGrugModsDir().listFiles(File::isDirectory);
        if (modDirs == null) {
            return null;
        }

        for (File modDir : modDirs) {
            File file = new File(modDir, "assets/" + namespace + "/models/" + path + ".json");
            if (file.isFile()) {
                try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception e) {
                    GrugModLoader.LOGGER.error("Failed to parse model JSON: " + file, e);
                    return null;
                }
            }
        }
        return null;
    }
}
