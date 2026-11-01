package net.grug.minecraft.stationapi.mixin;

import net.minecraft.client.gui.DrawContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Calls the screen's protected fill, which a mixin cannot shadow when it is inherited. */
@Mixin(DrawContext.class)
public interface GrugFillInvoker {

    /** Fills the rectangle, the way the screen's own drawing does. */
    @Invoker("fill")
    void grug$fill(int x1, int y1, int x2, int y2, int color);
}
