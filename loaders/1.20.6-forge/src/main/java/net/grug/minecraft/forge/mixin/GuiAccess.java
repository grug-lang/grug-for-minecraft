package net.grug.minecraft.forge.mixin;

import net.minecraft.client.gui.Gui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Temporary #294 instrumentation: reads the Gui's vignette brightness, which is the strength the
 * light-based vignette is drawn with. It is a smoothed function of the light at the camera entity,
 * so a capture can say what the wall's radial field is and how far it has settled. Not for
 * committing.
 */
@Mixin(Gui.class)
public interface GuiAccess {

    @Accessor("vignetteBrightness")
    float grug$vignetteBrightness();
}
