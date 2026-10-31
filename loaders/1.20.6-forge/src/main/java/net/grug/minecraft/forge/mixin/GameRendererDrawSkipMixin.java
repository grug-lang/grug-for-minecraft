package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.core.GrugDrawDemand;
import net.grug.minecraft.core.GrugTestCounters;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips a frame's whole draw, level and GUI, for a test that has not asked for a frame yet.
 *
 * <p>Experiment only: this mixin is on the throwaway branch that measures what the 142 tests which
 * never capture pay for drawing, and is deleted with it. The client tick, the packets it processes
 * and the runner tick all stay in {@code Minecraft.runTick}: only the call that draws is cancelled.
 * {@code GameRenderer.render} covers both the level draw (its chunk section compiles and their
 * upload, the light drain, entities and block entities) and the GUI and screen draws, which a test
 * that reads screen state rather than pixels does not need. The frame hook is {@code AFTER_LEVEL},
 * which fires inside the level draw, so a skipped frame is never counted as a drawn one.
 */
@Mixin(GameRenderer.class)
public class GameRendererDrawSkipMixin {

    @Inject(method = "render(FJZ)V", at = @At("HEAD"), cancellable = true)
    private void grug$skipFrameNoTestAskedFor(CallbackInfo ci) {
        if (!GrugDrawDemand.shouldDraw()) {
            GrugTestCounters.framesSkipped.incrementAndGet();
            ci.cancel();
        }
    }
}
