package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugSpriteFrames;
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

/**
 * Holds a legacy block face's texture coordinates inside its own atlas sprite.
 *
 * <p>Vanilla's face renderer computes every coordinate from the sprite's atlas position and the
 * block's bounding box. The low edge is the sprite's exact low edge and the high edge is a
 * hundredth of a texel inside it. StationAPI's atlas is minified with {@code GL_LINEAR}, so a
 * sample within half a texel of an edge blends in the sprite packed beside it: on the stationapi
 * wooden pipe capture the chest's face took the fire sprite's pixels, and fire regenerates its
 * pixels with {@code Math.random()} every tick, so one texel changed colour on every run (#919).
 *
 * <p>Clamping each coordinate to half a texel inside the sprite puts the sample at the centre of
 * its own edge texel, where the linear footprint covers that texel and nothing of the neighbour.
 * Interior coordinates are left exactly alone, so a face only moves where it would have sampled
 * across its sprite's boundary. The clamp is computed in {@link GrugSpriteFrames}, which the unit
 * test covers directly.
 *
 * <p>The sprite is the one StationAPI itself resolves for the face, including the destroy-stage
 * override, so the clamp follows the texture the face is actually drawn with.
 */
@GrugGenerated(
        "a mixin into the game's block face code: Mixin merges the injected methods into the"
            + " target, so the probes land on the target class and these lines are never counted")
@Mixin(BlockRenderManager.class)
abstract class BlockFaceUvMixin {

    /** The destroy-stage sprite while the mining overlay draws, and -1 otherwise. */
    @Shadow private int textureOverride;

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
    private void grug$clampVertex(
            Tessellator tessellator, double x, double y, double z, double u, double v) {
        Sprite sprite = grug$faceSprite.get();
        if (sprite != null) {
            u =
                    GrugSpriteFrames.clampToSprite(
                            sprite.getMinU(), sprite.getMaxU(), sprite.getContents().getWidth(), u);
            v =
                    GrugSpriteFrames.clampToSprite(
                            sprite.getMinV(),
                            sprite.getMaxV(),
                            sprite.getContents().getHeight(),
                            v);
        }
        tessellator.vertex(x, y, z, u, v);
    }
}
