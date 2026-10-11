package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the light-based vignette's strength while a screenshot run owns the client.
 *
 * <p>{@code Gui.updateVignetteBrightness} walks {@code vignetteBrightness} one percent of the way
 * toward {@code 1 - lightcurve(light at the camera entity)} every client tick, and {@code
 * renderVignette} draws {@code textures/misc/vignette.png} over the whole frame with a multiply
 * blend whose strength is that value. The coverage_render wall is lit by the room's torches, so the
 * light at the player's eye settles at 9 and the strength approaches 0.73 from the field's initial
 * 1.0, one percent a tick. A capture's strength therefore depends on how many ticks the light has
 * been settled for, which differs between a fresh pass and a later one, and the field's quantized
 * contours move with it: pass 1 and pass 2 of the same run render the wall up to a brightness step
 * apart, which is what makes a repeats run miss at pass 2. Pinning the value to zero, the neutral
 * multiply, makes every pass render the same flat wall, which is what the capture is comparing the
 * shape against. It is pinned the same way as the lightmap flicker: the value is zeroed right
 * before the frame is drawn with it. See #294.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Shadow private float vignetteBrightness;

    @Inject(method = "renderVignette", at = @At("HEAD"))
    private void grug$pinVignetteBrightness(
            GuiGraphics guiGraphics, Entity entity, CallbackInfo ci) {
        if (GrugModLoader.isTestRunActive()) {
            this.vignetteBrightness = 0.0F;
        }
    }
}
