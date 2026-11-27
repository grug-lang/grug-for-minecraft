package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugFrameRequest;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.grug.minecraft.ornithe.client.GrugOverlayHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The frame hook: {@code render} is the whole of Beta 1.7.3's frame, so its return is where the
 * settle answer samples what that frame left behind.
 *
 * <p>{@code setupFog} sets the distance fog up once a frame, and the fog's start is a quarter of
 * the render distance, so a large room would be tinted with the time-of-day fog colour and a
 * capture would depend on the render distance and the time. Pushing the start and end far past the
 * drawn world while a run owns the client removes that tint without touching the fog colour or the
 * enable bit. See #444.
 *
 * <p>See {@code ModLoaderAdapter#onFrameRendered} for why this is a frame hook and not a game tick.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void grug$onFrameRendered(CallbackInfo ci) {
        Minecraft minecraft = MinecraftInstance.get();
        if (minecraft.world != null && minecraft.screen == null) {
            GrugOverlayHost.drawHud(minecraft);
        }
        GrugCore.getAdapter().onFrameRendered();
    }

    /**
     * Skips a frame the running test has asked for none of, so a test that reads state rather than
     * pixels pays no draw: the level, its chunk rebuilds and the GUI are all inside {@code render}.
     *
     * <p>Cancelling here also skips the return injection above, so a frame nobody wanted is never
     * sampled as one a capture could read. The fog pin is part of the draw (Beta calls {@code
     * setupFog} from it), so it is skipped too and the next drawn frame pins it before drawing. See
     * {@code GrugFrameRequest} and #760.
     */
    @Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true)
    private void grug$skipUnwantedFrame(float partialTicks, CallbackInfo ci) {
        // Outside every display list, which is where a texture a chunk compile created is really
        // uploaded (GrugPendingUploads, #1096).
        net.grug.minecraft.ornithe.client.GrugModelRenderer.flushPendingUploads();
        if (!GrugFrameRequest.wanted()) {
            ci.cancel();
            return;
        }
        // A pending capture's pin is re-applied here, after the tick's physics and before the
        // draw, so the frame draws from the eye even when work later in the tick moved the player
        // (#1850).
        net.grug.minecraft.ornithe.control.GrugControlBetaClient.holdForFrame(
                MinecraftInstance.get());
    }

    /**
     * The world overlay hook: the snow and rain pass is the last world draw before the clouds, the
     * depth clear and the hand, so a line drawn at its return is depth-tested against the world. It
     * is a method of this class, so it needs no call-site target.
     */
    @Inject(method = "renderSnowAndRain(F)V", at = @At("RETURN"))
    private void grug$drawWorldOverlay(float partialTicks, CallbackInfo ci) {
        GrugOverlayHost.drawWorld(MinecraftInstance.get(), partialTicks);
    }

    @Inject(method = "setupFog(IF)V", at = @At("RETURN"))
    private void grug$disableFog(int mode, float partialTicks, CallbackInfo ci) {
        if (GrugClientHooks.isTestRunActive()) {
            GL11.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
            GL11.glFogf(GL11.GL_FOG_START, 1.0E9F);
            // The end must stay above the start, or the fixed-function division is 0/0.
            GL11.glFogf(GL11.GL_FOG_END, 2.0E9F);
        }
    }
}
