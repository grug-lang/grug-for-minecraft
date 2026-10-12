package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.core.GrugCore;
import net.minecraft.client.render.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The frame hook: {@code onFrameUpdate} ends by drawing the frame, so its return is where the
 * settle answer samples what that frame left behind.
 *
 * <p>See {@code ModLoaderAdapter#onFrameRendered} for why this is a frame hook and not a game tick.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "onFrameUpdate(F)V", at = @At("RETURN"))
    private void grug$onFrameRendered(CallbackInfo ci) {
        GrugCore.getAdapter().onFrameRendered();
    }
}
