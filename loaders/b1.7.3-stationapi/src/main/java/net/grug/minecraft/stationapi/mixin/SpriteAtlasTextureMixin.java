package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugReference;
import net.modificationstation.stationapi.api.client.texture.SpriteAtlasTexture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the game's animated sprites on the frame the texture pack stitched while a capture run owns
 * the client.
 *
 * <p>{@code tick} does nothing but advance every animated sprite of the atlas, water and lava among
 * them, and each advance uploads its next frame. A capture of a scene holding one would otherwise
 * be whichever frame the run happened to reach, so no committed golden could match the next run,
 * and the reference job, which compares its live capture with a golden exactly, could not match at
 * all. Without the tick the atlas keeps the first frame it was stitched with, which is the same
 * pixels in every run. Grug's own textures are stitched once and never animated, so they are
 * unaffected. See {@link GrugReference#isAnimationFrozen()} and #1771.
 */
@Mixin(SpriteAtlasTexture.class)
public class SpriteAtlasTextureMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void grug$freezeAnimations(CallbackInfo ci) {
        if (GrugReference.isAnimationFrozen()) {
            ci.cancel();
        }
    }
}
