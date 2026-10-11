package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.grug.minecraft.forge.GrugModLoader;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary #294 instrumentation, not for committing: for every section mesh uploaded while a test
 * run owns the client, logs a histogram of the per-vertex (colour, packed light) pairs the mesh
 * actually holds. The bake log says what the mesher was handed; this says what the renderer
 * uploads, so a stale or differently baked mesh cannot hide behind the bake log.
 */
@Mixin(com.mojang.blaze3d.vertex.VertexBuffer.class)
public abstract class CoverageMeshProbeMixin {

    @Unique private static final Logger GRUG_LOGGER = LogUtils.getLogger();

    @Inject(method = "upload", at = @At("HEAD"))
    private void grug$logMesh(BufferBuilder.RenderedBuffer buffer, CallbackInfo ci) {
        if (!GrugModLoader.isTestRunActive()) return;
        BufferBuilder.DrawState state = buffer.drawState();
        if (state.format() != DefaultVertexFormat.BLOCK) return;
        ByteBuffer data = buffer.vertexBuffer();
        if (data == null) return;
        int vertexCount = state.vertexCount();
        if (vertexCount == 0) return;
        VertexFormat format = state.format();
        int stride = format.getVertexSize();
        Map<String, Integer> histogram = new LinkedHashMap<>();
        int flat = 0;
        for (int i = 0; i < vertexCount; i++) {
            int base = i * stride;
            int r = data.get(base + 12) & 0xFF;
            int g = data.get(base + 13) & 0xFF;
            int b = data.get(base + 14) & 0xFF;
            // The light is UV2, which in the BLOCK layout sits at offset 28; offset 24 is UV1, the
            // overlay. Only flat-shaded vertices (the wall's faces and the like) are kept, so the
            // room's varied textures cannot flood the histogram.
            int light = data.getInt(base + 28);
            if (r != g || g != b || r < 100) continue;
            flat++;
            String key = r + "/" + light;
            histogram.merge(key, 1, Integer::sum);
        }
        GRUG_LOGGER.info(
                "[coverage-mesh] vertices={} flat={} distinct={} histogram={}",
                vertexCount,
                flat,
                histogram.size(),
                histogram);
    }
}
