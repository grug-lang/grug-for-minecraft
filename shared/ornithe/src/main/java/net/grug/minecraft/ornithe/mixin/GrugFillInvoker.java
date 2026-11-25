package net.grug.minecraft.ornithe.mixin;

import net.minecraft.client.gui.GuiElement;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Calls the screen's protected fill, which a mixin cannot shadow when it is inherited. */
@Mixin(GuiElement.class)
public interface GrugFillInvoker {

    /** Fills the rectangle, the way the screen's own drawing does. */
    @Invoker("fill")
    void grug$fill(int x1, int y1, int x2, int y2, int color);

    /** Fills the rectangle between two colours, the way the screen's own tooltip panel is drawn. */
    @Invoker("fillGradient")
    void grug$fillGradient(int x1, int y1, int x2, int y2, int color1, int color2);
}
