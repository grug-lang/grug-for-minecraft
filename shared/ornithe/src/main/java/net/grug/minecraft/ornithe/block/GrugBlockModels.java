package net.grug.minecraft.ornithe.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugFaceTextures;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.ornithe.GrugModLoader;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves which texture each face of a grug block uses by reading its model JSON (the same file
 * the Forge and StationAPI loaders use) straight from the grug mods directory.
 *
 * <p>Face indices match Block.getSprite(int side): 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
 *
 * <p>Which face key a built-in parent supplies, and what a face's key resolves to, is {@link
 * GrugFaceTextures}'s, so Java tests can cover those rules. What is left here is the part that
 * needs a running game: finding the model files in the mods directory and following their parents.
 */
public final class GrugBlockModels {

    private GrugBlockModels() {}

    /**
     * @return 6 texture references ("namespace:path", indexed by face), or null if the block has no
     *     usable model, in which case the caller should fall back to a single texture.
     */
    public static String[] resolveFaceTextures(String blockName) {
        Map<String, String> textures = new HashMap<>();
        String current = "grug:block/" + blockName;

        for (int depth = 0; depth < GrugFaceTextures.MAX_DEPTH; depth++) {
            JsonObject model = readModel(current);
            if (model == null) {
                return null;
            }

            // Children are visited first, so putIfAbsent lets them override parents
            JsonElement texturesElement = model.get("textures");
            if (texturesElement != null && texturesElement.isJsonObject()) {
                if (!GrugFaceTextures.mergeTextures(
                        blockName, texturesElement.getAsJsonObject(), textures)) {
                    return null;
                }
            }

            JsonElement parentElement = model.get("parent");
            if (parentElement == null) {
                return null;
            }
            if (!parentElement.isJsonPrimitive()
                    || !parentElement.getAsJsonPrimitive().isString()) {
                GrugModTreeDefect.report(
                        "Block '" + blockName + "' names a model 'parent' that is not a string.");
                return null;
            }
            String parent = parentElement.getAsString();

            String[] faceKeys = GrugFaceTextures.vanillaParentFaceKeys(parent);
            if (faceKeys != null) {
                String[] faces = GrugFaceTextures.resolve(blockName, faceKeys, textures);
                if (faces != null) {
                    GrugModLoader.LOGGER.info(
                            "Block '"
                                    + blockName
                                    + "' face textures (down, up, north, south, west, east): "
                                    + Arrays.toString(faces));
                }
                return faces;
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
                try (Reader reader =
                        Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(reader).getAsJsonObject();
                } catch (Exception e) {
                    // A model grug cannot read leaves the block on its single texture, which is a
                    // rendering nobody chose, so the run stops here rather than reporting a picture
                    // that no mod asked for.
                    throw Grug.fatal("Failed to read the block model " + file, e);
                }
            }
        }
        return null;
    }
}
