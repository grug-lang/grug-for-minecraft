package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.minecraft.client.render.GameRenderer;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The frame hook: {@code render} is the whole of Alpha's frame, so its return is where the settle
 * answer samples what that frame left behind.
 *
 * <p>{@code setupFog} sets the distance fog up once a frame, and the fog's start is a fraction of
 * the near plane, so a large room would be tinted with the time-of-day fog colour and a capture
 * would depend on the render distance and the time. Pushing the start and end far past the drawn
 * world while a run owns the client removes that tint without touching the fog colour or the enable
 * bit. See #444.
 *
 * <p>See {@code ModLoaderAdapter#onFrameRendered} for why this is a frame hook and not a game tick.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void grug$onFrameRendered(CallbackInfo ci) {
        GrugCore.getAdapter().onFrameRendered();
    }

    @Inject(method = "setupFog(I)V", at = @At("RETURN"))
    private void grug$disableFog(int mode, CallbackInfo ci) {
        if (GrugClientHooks.isTestRunActive()) {
            GL11.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
            GL11.glFogf(GL11.GL_FOG_START, 1.0E9F);
            GL11.glFogf(GL11.GL_FOG_END, 1.0E9F);
        }
    }
}
