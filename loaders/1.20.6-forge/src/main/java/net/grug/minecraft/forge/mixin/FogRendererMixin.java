package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.systems.RenderSystem;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pushes the distance fog out of the drawn world while a screenshot run owns the client.
 *
 * <p>{@code setupFog} derives the shader fog's start and end from the render distance and the
 * camera's surroundings, and the fog colour tracks the time of day, so a large room would be tinted
 * with that colour and a capture would depend on the render distance and the time. Setting the
 * start and end to {@link Float#MAX_VALUE} after the method has written them leaves the fog
 * effectively absent without touching the colour. See #444.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
    @Inject(
            method =
                    "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
            at = @At("RETURN"))
    private static void grug$disableFog(
            Camera camera,
            FogRenderer.FogMode mode,
            float renderDistance,
            boolean isFoggy,
            float partialTick,
            CallbackInfo ci) {
        if (GrugModLoader.isTestRunActive()) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        }
    }
}
