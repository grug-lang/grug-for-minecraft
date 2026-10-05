package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one block entity's geometry being built right now, and the boxes the draw_box host function
 * recorded while it was.
 *
 * <p>A render pass belongs to the loader, not to a script: the loader opens one before it calls a
 * block entity's render function, closes it afterwards, and draws what was recorded. That is the
 * only place a draw call can go, because the geometry has to end up in whatever the loader's
 * rendering stack is in the middle of building, and only the loader knows what that is.
 *
 * <p>There is at most one open pass, and it never spans a thread: the loader that opens it draws it
 * and closes it within one call, and a script cannot start a loader's render. Opening a second pass
 * anyway is a broken engine invariant rather than a mod mistake, so it throws.
 *
 * <p>Nothing here reports an error itself. A JVM test cannot call into the native adapter that
 * carries {@code Grug.hostFunctionErrorHappened}, so the reporting lives in the caller that can:
 * {@link HostFunctions} for a draw call outside a pass, and {@link GrugBlockGeometry} for a pass
 * that drew nothing.
 */
public final class GrugRenderPass {
    private static final List<GrugBox> BOXES = new ArrayList<>();
    private static boolean open = false;

    @GrugGenerated("utility class: never instantiated")
    private GrugRenderPass() {}

    /** Whether a render pass is open, which is the only state draw_box is legal in. */
    public static boolean isOpen() {
        return open;
    }

    /**
     * Opens a pass, with nothing recorded in it. Nesting is impossible from a script and
     * unreachable from a loader, so reaching it means the engine is broken, and that is reported as
     * an invariant rather than recovered from: a second pass would cross-contaminate one block's
     * boxes into another's geometry, and quietly dropping the first one's boxes would draw the
     * wrong shape without saying so.
     */
    public static void open() {
        if (open) {
            throw Grug.fatal("A block entity render pass is already open.");
        }
        BOXES.clear();
        open = true;
    }

    /**
     * Closes the pass and returns the boxes the render function recorded, in the order it drew
     * them.
     *
     * <p>A copy, so the boxes a loader is about to draw cannot be changed by the next pass, and so
     * that closing the pass does not hand out the live list.
     */
    public static List<GrugBox> close() {
        List<GrugBox> boxes = recorded();
        BOXES.clear();
        open = false;
        return boxes;
    }

    /**
     * Records one box.
     *
     * @return false when there is no open pass, or when the box is the wrong way round. The caller
     *     reports that rather than this class, and nothing is recorded either way.
     */
    public static boolean record(double x1, double y1, double z1, double x2, double y2, double z2) {
        if (!open) {
            return false;
        }
        return recordOrdered(new GrugBox(x1, y1, z1, x2, y2, z2));
    }

    /**
     * Whether a box's corners are the right way round, which is what makes it a box.
     *
     * <p>The first of each pair is the near corner and has to be no greater than the second. The
     * range is deliberately not part of this: a box that reaches past the block is a shape an
     * author may well want, and it is what makes an arm visible against a solid neighbour, because
     * Alpha draws a face the block does not reach unconditionally.
     *
     * <p>Pure, and the whole of the rule. {@link #recordOrdered} is what acts on the answer, and it
     * cannot be measured because nothing in the suite draws a reversed box.
     */
    public static boolean isOrdered(GrugBox box) {
        return box.x1() <= box.x2() && box.y1() <= box.y2() && box.z1() <= box.z2();
    }

    /**
     * Records the box if it is the right way round.
     *
     * <p>A box whose far corner is nearer than its near corner is refused rather than drawn,
     * because nothing else would say so. The engine reads the shape as it stands and builds each
     * face quad from it, so a reversed box comes out as a shape with faces missing rather than as
     * nothing, and the caller's "drew no geometry" report cannot catch it: a box was recorded, it
     * just is not the one that was asked for.
     */
    @GrugGenerated(
            "the wrong-way-round refusal: it needs a render pass holding a reversed box, which no"
                + " committed mod draws and no test can arrange, since a test cannot open a pass")
    private static boolean recordOrdered(GrugBox box) {
        if (!isOrdered(box)) {
            return false;
        }

        BOXES.add(box);
        return true;
    }

    /**
     * The boxes recorded so far, in the order they were drawn.
     *
     * <p>What a loader draws, and what a shape is built from one box at a time.
     */
    public static List<GrugBox> recorded() {
        return Collections.unmodifiableList(new ArrayList<>(BOXES));
    }
}
