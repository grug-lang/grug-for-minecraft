package net.grug.minecraft.forge.mixin;

import net.minecraft.client.gui.Gui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the two values 1.20.6 fades its world-save indicator with, which {@code Gui} keeps private.
 *
 * <p>A capture waits while the indicator would be drawn, so that the frame it reads cannot contain
 * the text an autosave leaves in the bottom right of the frame (#1132). The values are the ones
 * {@code Gui.tickAutosaveIndicator} writes and {@code Gui.renderSavingIndicator} draws with, not a
 * second copy of that state.
 */
@Mixin(Gui.class)
public interface GuiSavingIndicatorAccessor {
    @Accessor("autosaveIndicatorValue")
    float grug$savingIndicatorValue();

    @Accessor("lastAutosaveIndicatorValue")
    float grug$lastSavingIndicatorValue();
}
