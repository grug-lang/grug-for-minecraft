package net.grug.minecraft.forge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/**
 * Render types the overlay needs beyond the game's own.
 *
 * <p>Turning the depth test off through {@code RenderSystem} around a buffer is not enough: {@link
 * RenderType#lines()} carries the depth test in its own composite state, which is set up when the
 * buffer is flushed and would turn it back on. The type built here is that same type with a
 * no-depth-test shard in place of the default {@code LEQUAL_DEPTH_TEST}, so an export that asks for
 * no depth test really draws through terrain.
 *
 * <p>The class extends {@link RenderStateShard.DepthTestStateShard} rather than {@link
 * RenderStateShard} itself so that it is both the shard the composite state holds and the holder of
 * the render type. A plain {@code RenderStateShard} needs a pair of runnables passed to its
 * constructor, and this class has no work of its own to put in them: they would be empty, stored,
 * and never run, which nothing can cover. As a depth-test shard it hands the game's own setup and
 * clear runnables the {@code GL_ALWAYS} mode, the same pair the game's {@code NO_DEPTH_TEST} uses,
 * so the depth behaviour is unchanged and the code that implements it is the game's. Extending it
 * still extends {@code RenderStateShard} transitively, so {@code RENDERTYPE_LINES_SHADER}, {@code
 * NO_CULL} and the other protected shards named below stay reachable.
 */
final class GrugOverlayRenderTypes extends RenderStateShard.DepthTestStateShard {

    private static final GrugOverlayRenderTypes SHARD = new GrugOverlayRenderTypes();

    /** The game's line render type, with the depth test off and the rest of its state unchanged. */
    static final RenderType NO_DEPTH_LINES = SHARD.noDepthLines();

    /** The function name and GL mode of the game's own {@code NO_DEPTH_TEST} shard. */
    private GrugOverlayRenderTypes() {
        super("always", 519);
    }

    private RenderType noDepthLines() {
        return RenderType.create(
                "grug_no_depth_lines",
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                1536,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(this)
                        .createCompositeState(false));
    }
}
