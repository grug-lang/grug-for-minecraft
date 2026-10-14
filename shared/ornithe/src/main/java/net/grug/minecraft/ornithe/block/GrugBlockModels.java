package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.grug.GrugBlockFaces;
import net.grug.minecraft.ornithe.GrugModLoader;

import java.util.Arrays;

/**
 * Resolves which texture each face of a grug block uses by reading its model JSON (the same file
 * the Forge and StationAPI loaders use) straight from the grug mods directory.
 *
 * <p>The reading itself is {@link GrugBlockFaces}, which the other loaders that read the model
 * files share; this only supplies the Ornithe mods directory and logs the faces it found.
 */
public final class GrugBlockModels {

    private GrugBlockModels() {}

    /**
     * @return 6 texture references ("namespace:path", indexed by face), or null if the block has no
     *     usable model, in which case the caller should fall back to a single texture.
     */
    public static String[] resolveFaceTextures(String blockName) {
        String[] faces = GrugBlockFaces.resolve(GrugModLoader.getActiveGrugModsDir(), blockName);
        if (faces != null) {
            GrugModLoader.LOGGER.info(
                    "Block '"
                            + blockName
                            + "' face textures (down, up, north, south, west, east): "
                            + Arrays.toString(faces));
        }
        return faces;
    }

    /** Converts "grug:block/foo" to "assets/grug/textures/block/foo.png". */
    public static String toTexturePath(String textureRef) {
        int colon = textureRef.indexOf(':');
        String namespace = textureRef.substring(0, colon);
        String path = textureRef.substring(colon + 1);
        return "assets/" + namespace + "/textures/" + path + ".png";
    }
}
