package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.GrugClientHooks;
import net.minecraft.client.gui.hud.InGameHud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the vignette's strength while a test run owns the client.
 *
 * <p>{@code InGameHud.renderVignette} walks {@code vignetteDarkness} one percent of the way toward
 * {@code 1 - the light at the player's eyes} on every frame it draws, and multiplies the whole
 * frame by it, so a capture's strength depends on how many frames the game has drawn before it.
 * Zeroing the field right before the frame is drawn with it makes every capture render the same
 * strength, as 1.20.6's {@code GuiMixin} does for #294. See #308.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Shadow float vignetteDarkness;

    @Inject(method = "renderVignette", at = @At("HEAD"))
    private void grug$pinVignetteDarkness(
            float brightnessAtEyes, int width, int height, CallbackInfo ci) {
        if (GrugClientHooks.isRunActive()) {
            this.vignetteDarkness = 0.0F;
        }
    }
}
