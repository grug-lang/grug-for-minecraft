package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.BufferedReader;
import java.util.Optional;

/**
 * Temporary #294 instrumentation, not for committing: logs the fog uniforms and shader color the
 * solid chunk layer is drawn with, and once the runtime sources of the solid shader, so a capture
 * can be matched against the state its draw actually used instead of the state left behind after
 * the frame.
 */
@Mixin(LevelRenderer.class)
public abstract class CoverageDrawProbeMixin {

    @Unique private static final Logger GRUG_LOGGER = LogUtils.getLogger();

    @Unique private static long grug$lastLogSecond = -1L;

    @Unique private static boolean grug$loggedSources = false;

    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void grug$logSolidDraw(
            RenderType type,
            double x,
            double y,
            double z,
            Matrix4f projection,
            Matrix4f modelView,
            CallbackInfo ci) {
        if (!GrugModLoader.isTestRunActive()) return;
        if (type != RenderType.solid()) return;
        long second = System.currentTimeMillis() / 1000L;
        if (second == grug$lastLogSecond) return;
        grug$lastLogSecond = second;
        float[] fogColor = RenderSystem.getShaderFogColor();
        float[] color = RenderSystem.getShaderColor();
        GRUG_LOGGER.info(
                "[coverage-draw] fogStart={} fogEnd={} fogColor={},{},{},{} fogShape={}"
                        + " color={},{},{},{}",
                RenderSystem.getShaderFogStart(),
                RenderSystem.getShaderFogEnd(),
                fogColor[0],
                fogColor[1],
                fogColor[2],
                fogColor[3],
                RenderSystem.getShaderFogShape(),
                color[0],
                color[1],
                color[2],
                color[3]);
        if (!grug$loggedSources) {
            grug$loggedSources = true;
            grug$logSource("shaders/core/rendertype_solid.vsh");
            grug$logSource("shaders/core/rendertype_solid.fsh");
            grug$logSource("shaders/include/light.glsl");
            grug$logSource("shaders/include/fog.glsl");
        }
    }

    @Unique
    private static void grug$logSource(String path) {
        Optional<Resource> resource =
                Minecraft.getInstance()
                        .getResourceManager()
                        .getResource(new ResourceLocation(path));
        if (resource.isEmpty()) {
            GRUG_LOGGER.info("[coverage-source] {}: missing", path);
            return;
        }
        try (BufferedReader reader = resource.get().openAsReader()) {
            StringBuilder source = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                source.append(line).append('\n');
            }
            GRUG_LOGGER.info("[coverage-source] {}:\n{}", path, source);
        } catch (Exception e) {
            GRUG_LOGGER.info("[coverage-source] {}: read failed: {}", path, e.toString());
        }
    }
}
