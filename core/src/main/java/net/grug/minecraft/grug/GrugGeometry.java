package net.grug.minecraft.grug;

import java.util.Collections;
import java.util.List;

/**
 * What one block entity's render pass drew: the boxes draw_box recorded, which a loader draws with
 * the block's own texture, and the model faces draw_model recorded, which carry a texture of their
 * own.
 *
 * <p>Both lists are in the order the render function drew them, and neither can be changed, so the
 * geometry a loader is about to draw cannot be changed by the next pass.
 */
public final class GrugGeometry {
    private final List<GrugBox> boxes;
    private final List<GrugQuad> quads;

    public GrugGeometry(List<GrugBox> boxes, List<GrugQuad> quads) {
        this.boxes = Collections.unmodifiableList(boxes);
        this.quads = Collections.unmodifiableList(quads);
    }

    public List<GrugBox> boxes() {
        return boxes;
    }

    public List<GrugQuad> quads() {
        return quads;
    }

    /** Whether the pass drew nothing at all, which is a block that renders as nothing. */
    public boolean isEmpty() {
        return boxes.isEmpty() && quads.isEmpty();
    }
}
