package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.grug.GrugReference;
import net.minecraft.client.renderer.texture.TextureAtlas;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the game's animated sprites on their first frame while a capture run owns the client.
 *
 * <p>{@code tick} does nothing but {@code cycleAnimationFrames}, which advances every sprite the
 * atlases animate, water and lava among them. A capture of a scene holding one would otherwise be
 * whichever frame the run happened to reach, so no committed golden could match the next run, and
 * the reference job, which compares its live capture with a golden exactly, could not match at all.
 * Frozen, a tank's water column is the pack's own still-water tile in every run. See {@link
 * GrugReference#isAnimationFrozen()} and #1771.
 */
@Mixin(TextureAtlas.class)
public class TextureAtlasMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void grug$freezeAnimations(CallbackInfo ci) {
        if (GrugReference.isAnimationFrozen()) {
            ci.cancel();
        }
    }
}
