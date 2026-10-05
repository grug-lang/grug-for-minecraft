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
     * @return false when there is no open pass, which is the one way a draw call can arrive outside
     *     a render pass. The caller reports that rather than this class, and nothing is recorded.
     */
    public static boolean record(double x1, double y1, double z1, double x2, double y2, double z2) {
        if (!open) {
            return false;
        }
        BOXES.add(new GrugBox(x1, y1, z1, x2, y2, z2));
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
