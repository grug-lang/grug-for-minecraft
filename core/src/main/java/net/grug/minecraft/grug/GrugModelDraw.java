package net.grug.minecraft.grug;

import java.util.Collections;
import java.util.List;

/**
 * One model the draw_model host function recorded during a block entity's frame pass.
 *
 * <p>This is the per-frame half of {@code RenderContext.draw_model}: the faces are turned the same
 * way and moved by the same offsets, but they are drawn on top of the chunk geometry every frame
 * instead of being baked into it. A renderer that draws a part where the reference draws it needs
 * its vertices near the block rather than at the block's place in the world, and a chunk mesh
 * stores them at the latter; that is the difference between the two draw calls, not the shape.
 *
 * <p>The faces are already turned by core, so a loader only places them: the offsets are kept apart
 * from the corners because the reference translates its matrix by them before drawing, and a loader
 * that does the same adds them where the reference does.
 */
public final class GrugModelDraw implements GrugFrameDraw {
    private final List<GrugQuad> quads;
    private final double x;
    private final double y;
    private final double z;

    /**
     * @param quads the model's faces, turned by the call's angles
     * @param x how far to move the whole model along x, in blocks
     */
    public GrugModelDraw(List<GrugQuad> quads, double x, double y, double z) {
        this.quads = Collections.unmodifiableList(quads);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** The faces to draw, in the model's own order, already turned. */
    public List<GrugQuad> quads() {
        return quads;
    }

    /** How far to move the whole model along x, in blocks. */
    public double x() {
        return x;
    }

    /** How far to move the whole model along y, in blocks. */
    public double y() {
        return y;
    }

    /** How far to move the whole model along z, in blocks. */
    public double z() {
        return z;
    }
}
