package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugSpriteEdges;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.minecraft.block.Block;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.block.BlockRenderManager;
import net.modificationstation.stationapi.api.client.texture.Sprite;
import net.modificationstation.stationapi.api.client.texture.atlas.CustomAtlasProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds a legacy block face's texture coordinate off the atlas boundary it stands exactly on.
 *
 * <p>This version's face renderer computes every coordinate itself, from the sprite's atlas
 * position and the block's bounding box, and writes the low edge of a full face as the sprite's
 * exact low edge. StationAPI packs every block and item sprite into one 512-texel atlas, so the
 * texel on the other side of that boundary is whichever sprite the packer put there, and the atlas
 * magnifies with {@code GL_NEAREST} and minifies with {@code GL_LINEAR}, both of which reach across
 * the boundary: the chest face beside the fire sprite in {@code wooden_pipe_straight} took a fire
 * texel, and fire regenerates its pixels every tick, so one capture texel changed between runs
 * (#919).
 *
 * <p>{@link GrugSpriteEdges} moves a coordinate only when it stands exactly on an edge, half an
 * atlas texel in, which is the centre of the edge texel: every other coordinate is left where the
 * renderer put it, so an interior or sub-sprite coordinate and the hundredth of a texel inside a
 * full face's high edge are untouched.
 *
 * <p>The sprite is the one StationAPI itself resolves for the face, including the destroy-stage
 * override, so the correction follows the texture the face is actually drawn with.
 */
@GrugGenerated(
        "a mixin into the game's block face code: Mixin merges the injected methods into the"
            + " target, so the vertex and the sprite lookup land on the target class and are never"
            + " counted, and the wooden_pipe_shapes capture fails on stationapi without them")
@Mixin(BlockRenderManager.class)
abstract class BlockFaceEdgeMixin {

    /** The destroy-stage sprite while the mining overlay draws, and -1 otherwise. */
    @Shadow private int textureOverride;

    /**
     * The sprite of the face being drawn, or null when the block does not answer with one.
     *
     * <p>A thread local because the renderer is walked by the chunk compile thread while the
     * player's own chunk is compiled on the client thread.
     */
    @Unique private final ThreadLocal<Sprite> grug$faceSprite = new ThreadLocal<>();

    @Inject(
            method = {
                "renderBottomFace",
                "renderTopFace",
                "renderEastFace",
                "renderWestFace",
                "renderNorthFace",
                "renderSouthFace"
            },
            at = @At("HEAD"))
    private void grug$captureSprite(
            Block block, double x, double y, double z, int textureId, CallbackInfo ci) {
        grug$faceSprite.remove();
        if (!(block instanceof CustomAtlasProvider)) return;
        int id = textureOverride >= 0 ? textureOverride : textureId;
        grug$faceSprite.set(((CustomAtlasProvider) block).getAtlas().getTexture(id).getSprite());
    }

    @Inject(
            method = {
                "renderBottomFace",
                "renderTopFace",
                "renderEastFace",
                "renderWestFace",
                "renderNorthFace",
                "renderSouthFace"
            },
            at = @At("RETURN"))
    private void grug$clearSprite(
            Block block, double x, double y, double z, int textureId, CallbackInfo ci) {
        grug$faceSprite.remove();
    }

    /** Whether the diagnostic below has reported the u axis, so it reports that one once. */
    @Unique private final AtomicBoolean grug$reportedU = new AtomicBoolean();

    /** The same for the v axis. */
    @Unique private final AtomicBoolean grug$reportedV = new AtomicBoolean();

    /**
     * Reports the first coordinate this moves, once per axis, with the sprite it moved on.
     *
     * <p>A diagnostic of #919, not a defect report: it says whether the correction reached a face
     * at all and what range that face's coordinates are in, which is what the measurement needs. It
     * goes when #919 is settled.
     */
    @Unique
    private void grug$report(
            String axis,
            AtomicBoolean reported,
            double before,
            double after,
            Sprite sprite,
            float min,
            float max,
            int texels) {
        if (before == after || !reported.compareAndSet(false, true)) return;
        InitListener.LOGGER.info(
                "Held a {} world coordinate off its sprite's edge of the atlas: {} to {}, on a"
                        + " sprite spanning {} to {} and {} texels across.",
                axis,
                before,
                after,
                min,
                max,
                texels);
    }

    @Redirect(
            method = {
                "renderBottomFace",
                "renderTopFace",
                "renderEastFace",
                "renderWestFace",
                "renderNorthFace",
                "renderSouthFace"
            },
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/render/Tessellator;vertex(DDDDD)V"))
    private void grug$edgeCoordinate(
            Tessellator tessellator, double x, double y, double z, double u, double v) {
        Sprite sprite = grug$faceSprite.get();
        if (sprite != null) {
            double heldU =
                    GrugSpriteEdges.edgeCoordinate(
                            u, sprite.getMinU(), sprite.getMaxU(), sprite.getContents().getWidth());
            double heldV =
                    GrugSpriteEdges.edgeCoordinate(
                            v,
                            sprite.getMinV(),
                            sprite.getMaxV(),
                            sprite.getContents().getHeight());
            grug$report(
                    "u",
                    grug$reportedU,
                    u,
                    heldU,
                    sprite,
                    sprite.getMinU(),
                    sprite.getMaxU(),
                    sprite.getContents().getWidth());
            grug$report(
                    "v",
                    grug$reportedV,
                    v,
                    heldV,
                    sprite,
                    sprite.getMinV(),
                    sprite.getMaxV(),
                    sprite.getContents().getHeight());
            u = heldU;
            v = heldV;
        }
        tessellator.vertex(x, y, z, u, v);
    }
}
