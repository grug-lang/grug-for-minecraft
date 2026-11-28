package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.client.GrugGuiScale;
import net.minecraft.client.render.Window;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test only: lets {@code TestTickContext.set_gui_scale} fix the scale this version's window
 * computes, which is the one place alpha 1.1.2_01 reads it (#1752).
 *
 * <p>The original constructor works the factor out from the framebuffer as it goes, so the result
 * is replaced after the body ran, from the arguments it started with. While no test has forced a
 * scale the fields the body wrote are the fields that stay, so a normal game is unchanged.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Shadow private int width;

    @Shadow private int height;

    @Shadow public int scale;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void grug$forcedScale(int width, int height, CallbackInfo ci) {
        Integer forced = GrugGuiScale.forced();
        if (forced == null) return;
        int scale = forced.intValue();
        this.width = width / scale;
        this.height = height / scale;
        this.scale = scale;
    }
}
