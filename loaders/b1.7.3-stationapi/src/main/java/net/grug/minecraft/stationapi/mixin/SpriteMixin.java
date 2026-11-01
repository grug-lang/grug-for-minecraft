package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugSpriteFrames;
import net.modificationstation.stationapi.api.client.texture.Sprite;
import net.modificationstation.stationapi.api.client.texture.SpriteContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a baked model face's UVs inside its own atlas sprite.
 *
 * <p>{@code BakedQuadFactory} turns a model face's UVs, which run from 0 to 16, into atlas
 * coordinates through {@code getFrameU} and {@code getFrameV}, and those mapped 0 onto the sprite's
 * low edge and 16 onto its high edge. A texture is sampled at the point the UV names, so the high
 * edge is the boundary between this sprite and whatever sprite the stitcher packed beside it; with
 * nearest filtering the corner of a face can take the neighbouring sprite's first texel. See #919:
 * the stationapi atlas packed the fire sprite beside the chest sprite, and fire fills its pixels
 * with Math.random() every tick, so a single pixel of {@code wooden_pipe_straight} was a different
 * colour on every run.
 *
 * <p>Holding UV 0 and UV 16 just inside the sprite's own edges, in {@link GrugSpriteFrames}, keeps
 * every face sample in its own sprite without moving an interior UV, so models that take a
 * sub-rectangle of a larger sheet render exactly as they did. Every block model on this loader goes
 * through these two methods, so this reaches every model face without touching the sprite positions
 * the atlas assigns.
 */
@GrugGenerated(
        "a mixin into the game's sprite code: Mixin merges the injected methods into the target, so"
                + " the probes land on the target class and these lines are never counted")
@Mixin(Sprite.class)
abstract class SpriteMixin {

    @Shadow
    public abstract SpriteContents getContents();

    @Shadow
    public abstract float getMinU();

    @Shadow
    public abstract float getMaxU();

    @Shadow
    public abstract float getMinV();

    @Shadow
    public abstract float getMaxV();

    @Inject(method = "getFrameU", at = @At("RETURN"), cancellable = true)
    private void grug$insetFrameU(double frame, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(
                GrugSpriteFrames.frameCoordinate(
                        getMinU(), getMaxU(), getContents().getWidth(), frame));
    }

    @Inject(method = "getFrameV", at = @At("RETURN"), cancellable = true)
    private void grug$insetFrameV(double frame, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(
                GrugSpriteFrames.frameCoordinate(
                        getMinV(), getMaxV(), getContents().getHeight(), frame));
    }
}
