package net.grug.minecraft.grug;

import java.util.List;

/**
 * Runs a block entity's render pass and hands back the geometry it drew.
 *
 * <p>The pass belongs to the loader, because the geometry has to land in whatever the loader's
 * rendering stack is in the middle of building and only the loader knows what that is. What is here
 * is the game-independent half of it: open the pass, call the render function, close it, and report
 * a pass that produced nothing to draw.
 */
public final class GrugBlockGeometry {

    @GrugGenerated("utility class: never instantiated")
    private GrugBlockGeometry() {}

    /**
     * Draws {@code entityHandle}'s render function and returns the boxes it drew.
     *
     * <p>Reports, rather than throws, when there is nothing to draw. Two mistakes leave a block
     * with no geometry at all: a block that declared custom rendering without a block entity
     * script, and a block entity whose script has no render function or one that draws nothing.
     * Both are reported, because a block that renders as nothing is not a shape anyone chose and
     * silence would let it ship. The sandbox is that the script carries on afterwards.
     *
     * <p>Neither report can go through {@link Grug#hostFunctionErrorHappened}. That reports from
     * inside a grug call, and by here the render function has returned and grug has already popped
     * the frame it pushed, so the call stack this would report against is empty and grug aborts the
     * game rather than failing the run. {@link GrugModTreeDefect} is the reporting channel that
     * works from outside a call.
     */
    public static List<GrugBox> draw(long entityHandle, long renderFnId) {
        GrugRenderPass.open();

        // Behind a method of its own, so that what is excluded is the call into the VM and not the
        // pass bookkeeping around it, which every run reaches.
        boolean ranRender = runRender(entityHandle, renderFnId);

        List<GrugBox> boxes = GrugRenderPass.close();

        // Behind a method of its own for the same reason: the choice between two reports is not
        // something a run exercises, and excluding it here keeps the pass itself measured.
        reportNoGeometry(ranRender, boxes);

        return boxes;
    }

    /**
     * Runs the render function, and answers whether it ran.
     *
     * <p>A block that declared custom rendering without a block entity script has no entity to run
     * a render function on, and grug resolves an export id by name across every file it has loaded,
     * so the id can be valid even for a block entity script that has no render. Passing the zero
     * handle on would hand the native side a null entity pointer, which is why this checks and
     * answers rather than calling.
     *
     * <p>Excluded because a handle that is not zero means a live grug entity, which only a game
     * has, and a Java test can only reach the half that declines to call.
     */
    @GrugGenerated(
            "the call into the VM: it needs a live grug entity, so only a game reaches it, and the"
                    + " zero-handle half of the guard is what the Java tests cover")
    private static boolean runRender(long entityHandle, long renderFnId) {
        if (entityHandle == 0 || renderFnId == Grug.INVALID_GRUG_EXPORT_FN_ID) {
            return false;
        }

        Grug.callExportFn(entityHandle, renderFnId);
        return true;
    }

    /**
     * Reports the one mistake that applies, if either does. A pass that ran and drew nothing and a
     * pass that never ran at all are different mistakes with different fixes, and a caller only
     * ever hits one of them.
     *
     * <p>Excluded from coverage because the second half of the choice needs a pass that ran, which
     * means a live grug entity, which only a game has. The first half is what the Java tests cover.
     */
    @GrugGenerated(
            "one report's branch of the pair: a pass that ran and drew nothing needs a live grug"
                    + " entity to have run at all, so only a game reaches it")
    private static void reportNoGeometry(boolean ranRender, List<GrugBox> boxes) {
        if (!ranRender) {
            GrugModTreeDefect.report(
                    "set_custom_render: the block declared custom rendering, but it has no block"
                        + " entity, so there is no render() to draw with and the block renders as"
                        + " nothing. Either add the block entity with set_block_entity, or drop the"
                        + " set_custom_render() call.");
        } else if (boxes.isEmpty()) {
            GrugModTreeDefect.report(
                    "set_custom_render: the block declared custom rendering, but its block entity's"
                            + " render() drew no geometry, so the block renders as nothing. Either"
                            + " add a render() that calls draw_box, or drop the set_custom_render()"
                            + " call.");
        }
    }
}
