package net.grug.minecraft.grug;

import java.util.Collections;
import java.util.List;

/**
 * What one block entity's frame pass drew: the stacks draw_item recorded and the models draw_model
 * recorded, in the order it drew them.
 *
 * <p>A frame pass draws on top of the chunk geometry every frame, so nothing here is baked and
 * nothing needs rebuilding: the loader draws what this holds straight away and throws it away.
 * There is no error for a frame pass that drew nothing, unlike a chunk pass: a machine with no item
 * in flight has nothing to draw.
 */
public final class GrugFrameGeometry {
    private final List<GrugFrameDraw> draws;

    public GrugFrameGeometry(List<GrugFrameDraw> draws) {
        this.draws = Collections.unmodifiableList(draws);
    }

    /**
     * The draws to make, in the order the script drew them.
     *
     * <p>Each is a {@link GrugItemDraw} or a {@link GrugModelDraw}, and a later one covers an
     * earlier one where they overlap, so a loader draws them in this order and not grouped by kind.
     */
    public List<GrugFrameDraw> draws() {
        return draws;
    }
}
