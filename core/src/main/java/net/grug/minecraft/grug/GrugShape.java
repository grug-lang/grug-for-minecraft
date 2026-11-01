package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The boxes one call of a block's shape export declared: the boxes entities collide with and the
 * boxes the game targets and outlines.
 *
 * <p>A box is in block-local coordinates, 0 to 1 from the block's minimum corner, so a loader adds
 * the block's own position to get world coordinates. A block may declare as many boxes as it likes
 * on either axis pair: a fence has several posts, and a table one box per leg.
 *
 * <p>A script that declares no selection box means the collision boxes, which is the game's own
 * default too: the shape the game targets is the shape it collides with. Without that rule a block
 * author who declared only collision boxes would get a block that cannot be clicked or outlined,
 * which nobody means. The other way round is a real case as well: a script may declare a selection
 * box and no collision box, so a shape always has at least one selection box while its collision
 * list may be empty.
 */
public final class GrugShape {
    private final List<GrugBox> collision;
    private final List<GrugBox> selection;

    /**
     * @param collision the boxes entities collide with, in the order the script declared them
     * @param selection the boxes the game targets and outlines, or an empty list to use {@code
     *     collision}
     */
    public GrugShape(List<GrugBox> collision, List<GrugBox> selection) {
        this.collision = Collections.unmodifiableList(new ArrayList<>(collision));
        this.selection =
                Collections.unmodifiableList(
                        new ArrayList<>(selection.isEmpty() ? collision : selection));
    }

    /** The boxes entities collide with. */
    public List<GrugBox> collision() {
        return collision;
    }

    /** The boxes the game targets and outlines. */
    public List<GrugBox> selection() {
        return selection;
    }
}
