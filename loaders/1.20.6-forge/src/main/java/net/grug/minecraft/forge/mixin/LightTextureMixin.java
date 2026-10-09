package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.renderer.LightTexture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the lightmap's block-light flicker while a screenshot run owns the client.
 *
 * <p>{@code LightTexture.tick} walks {@code blockLightRedFlicker} by a small random step every
 * frame, and {@code updateLightTexture} multiplies the block-light color by {@code
 * blockLightRedFlicker + 1.5}. The whole block-lit scene therefore rides that walk, which is what
 * makes the same capture land about a light step brighter or darker from run to run. It is the
 * flicker 1.2.5 pins through {@code GrugTorchFlicker}, at a tenth of the step, and it is pinned the
 * same way: the value is zeroed, which is the neutral 1.5 factor, right before the lightmap is
 * built. See #253.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {

    @Shadow private float blockLightRedFlicker;

    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void grug$pinBlockLightFlicker(float partialTicks, CallbackInfo ci) {
        if (GrugModLoader.isTestRunActive()) {
            this.blockLightRedFlicker = 0.0F;
        }
    }
}
