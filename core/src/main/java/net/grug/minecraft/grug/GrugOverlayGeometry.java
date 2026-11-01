package net.grug.minecraft.grug;

import java.util.Collections;
import java.util.List;

/**
 * What one overlay's render pass drew: the item icons and highlight boxes its render() recorded, in
 * the order it drew them.
 *
 * <p>An overlay pass draws on top of the screen every frame, so nothing here is baked and nothing
 * needs rebuilding: the loader draws what this holds straight away and throws it away. There is no
 * error for a pass that drew nothing, unlike a chunk pass: a frame with nothing new has nothing to
 * draw, and an overlay that is not hovering anything draws no highlight.
 */
public final class GrugOverlayGeometry {
    private final List<GrugScreenItemDraw> items;
    private final List<GrugScreenBox> boxes;

    public GrugOverlayGeometry(List<GrugScreenItemDraw> items, List<GrugScreenBox> boxes) {
        this.items = Collections.unmodifiableList(items);
        this.boxes = Collections.unmodifiableList(boxes);
    }

    /** The item icons to draw, in the order the script drew them. */
    public List<GrugScreenItemDraw> items() {
        return items;
    }

    /** The highlight boxes to draw, in the order the script drew them. */
    public List<GrugScreenBox> boxes() {
        return boxes;
    }
}
