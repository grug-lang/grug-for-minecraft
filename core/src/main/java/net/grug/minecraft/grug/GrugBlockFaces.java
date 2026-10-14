package net.grug.minecraft.grug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Finds the texture each face of a grug block uses by reading its model JSON from the mods
 * directory and following its {@code parent} chain.
 *
 * <p>This is the file-reading half of the rule. Which face key a built-in parent supplies, and what
 * a face's key resolves to, is {@link GrugFaceTextures}'s, so Java tests can cover those rules
 * without a model on disk. Every loader that draws a custom-rendered block from its model's
 * textures goes through here, so the same model gives the same faces on each of them.
 */
public final class GrugBlockFaces {

    @GrugGenerated("utility class: never instantiated")
    private GrugBlockFaces() {}

    /**
     * The texture references of the block named {@code blockName}, indexed by face, or null if the
     * block has no usable model, in which case the caller should fall back to a single texture.
     *
     * <p>Face indices match {@code Block.getSprite(int side)}: 0 down, 1 up, 2 north, 3 south, 4
     * west, 5 east. A reference is returned as written, namespace and all, so the caller decides
     * whether a file behind it is shipped.
     *
     * @param modsDir the grug mods directory the model is looked for in
     */
    public static String[] resolve(File modsDir, String blockName) {
        Map<String, String> textures = new HashMap<>();
        String current = "grug:block/" + blockName;

        for (int depth = 0; depth < GrugFaceTextures.MAX_DEPTH; depth++) {
            JsonObject model = readModel(modsDir, current);
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
                return GrugFaceTextures.resolve(blockName, faceKeys, textures);
            }

            // Not a built-in parent, so it has to be another grug model
            current = parent;
        }

        return null;
    }

    /**
     * The model {@code modelId} names, read from the first mod that ships it, or null when no mod
     * ships it. A model that is there but cannot be read stops the run: the block would otherwise
     * be drawn with a texture nobody chose.
     */
    private static JsonObject readModel(File modsDir, String modelId) {
        int colon = modelId.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : modelId.substring(0, colon);
        String path = colon < 0 ? modelId : modelId.substring(colon + 1);

        File file =
                GrugResourceIndex.findDirectResource(
                        modsDir, "assets/" + namespace + "/models/" + path + ".json");
        if (file == null) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw Grug.fatal("Failed to read the block model " + file, e);
        }
    }
}
