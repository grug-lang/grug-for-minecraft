package net.grug.minecraft.stationapi.block;

import net.grug.minecraft.grug.GrugBlockFaces;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.stationapi.events.init.InitListener;

import java.util.Arrays;

/**
 * Resolves which texture each face of a grug block uses by reading its model JSON straight from the
 * grug mods directory.
 *
 * <p>This loader needs it for a block that declares custom rendering: the shape is drawn through
 * the block's own sprite, so the block has to be told which terrain-atlas slot each face's texture
 * lives in. StationAPI's model bake normally does that work, but it does not reach a custom-drawn
 * shape, and its result is not something to depend on: the bake can fall back to the vanilla model
 * for a block whose assets were not in that reload, and the shape would then draw with the missing
 * sprite. Reading the model file is the same answer every reload.
 *
 * <p>Face indices match {@code Block.getTexture(int side)}: 0 down, 1 up, 2 north, 3 south, 4 west,
 * 5 east. The reading itself is {@link GrugBlockFaces}, which the other loaders that read the model
 * files share; this only supplies StationAPI's mods directory and logs the faces it found.
 */
public final class GrugBlockModels {

    @GrugGenerated("utility class: never instantiated")
    private GrugBlockModels() {}

    /**
     * @return 6 texture references ("namespace:path", indexed by face), or null if the block has no
     *     usable model.
     */
    public static String[] resolveFaceTextures(String blockName) {
        String[] faces = GrugBlockFaces.resolve(InitListener.getActiveGrugModsDir(), blockName);
        if (faces != null) {
            InitListener.LOGGER.info(
                    "Block '"
                            + blockName
                            + "' face textures (down, up, north, south, west, east): "
                            + Arrays.toString(faces));
        }
        return faces;
    }
}
