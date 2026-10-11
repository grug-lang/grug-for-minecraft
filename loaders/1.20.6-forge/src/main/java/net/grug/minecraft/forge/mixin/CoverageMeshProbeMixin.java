package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;

import net.grug.minecraft.forge.GrugModLoader;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Temporary #294 instrumentation, not for committing: for every section mesh uploaded while a test
 * run owns the client, logs a histogram of the per-vertex (colour, packed light) pairs the mesh
 * actually holds. The bake log says what the mesher was handed; this says what the renderer
 * uploads, so a stale or differently baked mesh cannot hide behind the bake log.
 *
 * <p>The BLOCK layout is Position (0), Color (12), UV0 (16), UV2 (24), Normal (28), Padding (31):
 * the packed light is UV2 at offset 24, and offset 28 holds the normal. Only flat-shaded vertices
 * (r == g == b, r >= 100) are kept, so the room's varied textures cannot flood the histogram, and a
 * second histogram covers only the coverage_render wall's own vertices (a north face whose
 * section-local z is 10, the wall's plane at world z 58 in a section whose z index is 3).
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
        Map<String, Integer> wallHistogram = new LinkedHashMap<>();
        int flat = 0;
        for (int i = 0; i < vertexCount; i++) {
            int base = i * stride;
            int r = data.get(base + 12) & 0xFF;
            int g = data.get(base + 13) & 0xFF;
            int b = data.get(base + 14) & 0xFF;
            if (r != g || g != b || r < 100) continue;
            flat++;
            int light = data.getInt(base + 24);
            histogram.merge(r + "/" + light, 1, Integer::sum);
            // The wall's north faces: the normal is (0, 0, -127) and the face plane is the
            // section-local z 10 the wall sits on. Other blocks at z = 10 mod 16 (the terrain at
            // z 74, say) have a sky-lit light and are told apart by the light value.
            if (data.get(base + 28) == 0
                    && data.get(base + 29) == 0
                    && data.get(base + 30) == (byte) -127
                    && Float.floatToIntBits(data.getFloat(base + 8))
                            == Float.floatToIntBits(10.0F)) {
                wallHistogram.merge(r + "/" + light, 1, Integer::sum);
            }
        }
        GRUG_LOGGER.info(
                "[coverage-mesh] vertices={} flat={} distinct={} wall={} histogram={}",
                vertexCount,
                flat,
                histogram.size(),
                wallHistogram,
                histogram);
    }
}
