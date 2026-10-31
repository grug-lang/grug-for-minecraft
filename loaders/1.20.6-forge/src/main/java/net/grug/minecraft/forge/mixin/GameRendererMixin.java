package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.core.GrugFrameRequest;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips a frame the running test has asked for none of, so a test that reads state rather than
 * pixels pays no draw.
 *
 * <p>{@code GameRenderer.render} is the whole frame: the light drain, the chunk section compiles
 * and their uploads, entities, block entities, the GUI and any screen. The client tick, the packets
 * it processes and the runner tick all stay in {@code Minecraft.runTick}, which is what a test
 * advances on. The frame hook is {@code RenderLevelStageEvent.AFTER_LEVEL}, which fires inside the
 * level draw, so a skipped frame is never counted as one a capture could read. See {@code
 * GrugFrameRequest} and #760.
 *
 * <p>Cancelling at HEAD also skips the per-frame pins (the lightmap flicker, the vignette, the fog
 * distance), which only a drawn frame uses, and the next drawn frame pins them again before it
 * draws.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render(FJZ)V", at = @At("HEAD"), cancellable = true)
    private void grug$skipUnwantedFrame(CallbackInfo ci) {
        if (!GrugFrameRequest.wanted()) {
            ci.cancel();
        }
    }
}
