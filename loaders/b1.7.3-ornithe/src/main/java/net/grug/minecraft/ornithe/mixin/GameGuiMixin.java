package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.GrugClientHooks;
import net.minecraft.client.gui.GameGui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the vignette's strength while a test run owns the client.
 *
 * <p>{@code GameGui.renderVignette} walks {@code vignetteBrightness} one percent of the way toward
 * {@code 1 - the light at the player's eyes} on every frame it draws, and draws {@code
 * misc/vignette.png} over the whole frame with a multiply blend whose strength is that value. The
 * field starts at 1.0 and converges over several hundred frames, so a capture's strength depends on
 * how many frames the game has drawn before it, which differs between runs and between suite
 * passes. The darkening is a fraction of one 8-bit step on most of the crop, so a capture lands a
 * step off exactly on the pixels whose rounding sits at that boundary. Zeroing the field right
 * before the frame is drawn with it makes every capture render the same strength. This is the pin
 * 1.20.6's {@code GuiMixin} applies for #294. See #308.
 */
@Mixin(GameGui.class)
public abstract class GameGuiMixin {

    @Shadow float vignetteBrightness;

    @Inject(method = "renderVignette", at = @At("HEAD"))
    private void grug$pinVignetteBrightness(
            float brightnessAtEyes, int width, int height, CallbackInfo ci) {
        if (GrugClientHooks.isTestRunActive()) {
            this.vignetteBrightness = 0.0F;
        }
    }
}
