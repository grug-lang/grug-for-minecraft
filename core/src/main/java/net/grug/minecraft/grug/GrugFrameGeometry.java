package net.grug.minecraft.grug;

import java.util.Collections;
import java.util.List;

/**
 * What one block entity's frame pass drew: the stacks draw_item recorded, in the order it drew
 * them.
 *
 * <p>A frame pass draws on top of the chunk geometry every frame, so nothing here is baked and
 * nothing needs rebuilding: the loader draws what this holds straight away and throws it away.
 * There is no error for a frame pass that drew nothing, unlike a chunk pass: a machine with no item
 * in flight has nothing to draw.
 */
public final class GrugFrameGeometry {
    private final List<GrugItemDraw> items;

    public GrugFrameGeometry(List<GrugItemDraw> items) {
        this.items = Collections.unmodifiableList(items);
    }

    /** The stacks to draw, in the order the script drew them. */
    public List<GrugItemDraw> items() {
        return items;
    }
}
