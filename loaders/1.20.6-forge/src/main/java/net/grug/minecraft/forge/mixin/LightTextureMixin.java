package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
 *
 * <p>The lightmap also scales the sky light by {@code ClientLevel.getSkyDarken}, which follows the
 * world's clock and the weather. The clock runs while a suite does, so a capture of a sky-lit
 * square came out one brightness when the test ran early in the day and a darker one at dusk, about
 * 13 of 255 per channel across a whole capture, and a golden matched only the runs that landed in
 * the same part of the day. A run pins it to the clear noon value, 1.0, so the sky light a capture
 * shows does not depend on when in the game day the test happened to run.
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

    @Redirect(
            method = "updateLightTexture",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/multiplayer/ClientLevel;getSkyDarken(F)F"))
    private float grug$pinSkyDarken(ClientLevel level, float partialTicks) {
        if (GrugModLoader.isTestRunActive()) {
            return 1.0F;
        }
        return level.getSkyDarken(partialTicks);
    }
}
