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
     * <p>Reports, rather than throws, when there is nothing to draw. A block that declared custom
     * rendering can end up with nothing to draw for three separate reasons, and each names the call
     * that would fix it. All three are reported, because a block that renders as nothing is not a
     * shape anyone chose and silence would let it ship. The sandbox is that the script carries on
     * afterwards.
     *
     * <p>None of these reports can go through {@link Grug#hostFunctionErrorHappened}. That reports
     * from inside a grug call, and by here the render function has returned and grug has already
     * popped the frame it pushed, so the call stack this would report against is empty and grug
     * aborts the game rather than failing the run. {@link GrugModTreeDefect} is the reporting
     * channel that works from outside a call.
     */
    public static List<GrugBox> draw(long entityHandle, long renderFnId) {
        GrugRenderPass.open();

        // The pass closes even when the render call does not come back, because callExportFn
        // rethrows a fatal raised inside the script once the native call returns to Java. A pass
        // left open would make the next block's open() report a nesting invariant rather than the
        // defect that actually happened. The fatal still propagates: this is about which message
        // the player reads, not about swallowing anything.
        boolean canRender = entityHandle != 0 && renderFnId != Grug.INVALID_GRUG_EXPORT_FN_ID;

        boolean ranRender;
        List<GrugBox> boxes;
        try {
            // A block that declared custom rendering without a block entity script has no entity to
            // run a render function on, and grug resolves an export id by name across every file it
            // has loaded, so the id can be valid even for a block entity script that has no render.
            // Passing the zero handle on would hand the native side a null entity pointer.
            ranRender = false;
            if (canRender) {
                callRender(entityHandle, renderFnId);
                ranRender = true;
            }
        } finally {
            boxes = GrugRenderPass.close();
        }

        // Behind a method of its own, so that what is excluded is the choice between the reports
        // and not the pass bookkeeping around it, which every run reaches.
        reportNoGeometry(entityHandle, renderFnId, ranRender, boxes);

        return boxes;
    }

    /**
     * Calls the render function.
     *
     * <p>A method of its own so the exclusion is the call rather than the guard in front of it.
     * That guard is the safety-critical line of this fix and a Java test does reach it, so it stays
     * where the coverage gate can see it, along with the branch that decides whether to call. This
     * needs a live grug entity, which only a game has.
     */
    @GrugGenerated("the call into the VM: it needs a live grug entity, so only a game reaches it")
    private static void callRender(long entityHandle, long renderFnId) {
        Grug.callExportFn(entityHandle, renderFnId);
    }

    /**
     * Reports the mistake that applies, if any does.
     *
     * <p>Three of them, and the two that cannot both apply are told apart because the fix differs:
     * a block with no block entity needs {@code set_block_entity}, while a block entity whose
     * script has no {@code render()} needs the export itself. Telling those two apart matters
     * because one of them is a call the author has already made, and being told to add it is as
     * unhelpful as being told nothing.
     *
     * <p>Excluded because two of the three arms need a pass that ran, which means a live grug
     * entity, which only a game has. The arm that a Java test can reach is the one for a block with
     * no block entity at all.
     */
    @GrugGenerated(
            "two of the three reports need a pass that ran, so only a game reaches them; the third"
                    + " is what GrugBlockGeometryTest covers")
    private static void reportNoGeometry(
            long entityHandle, long renderFnId, boolean ranRender, List<GrugBox> boxes) {
        if (!ranRender) {
            if (entityHandle == 0) {
                GrugModTreeDefect.report(
                        "set_custom_render: the block declared custom rendering, but it has no"
                            + " block entity, so there is no render() to draw with and the block"
                            + " renders as nothing. Either add the block entity with"
                            + " set_block_entity, or drop the set_custom_render() call.");
            } else {
                GrugModTreeDefect.report(
                        "set_custom_render: the block declared custom rendering, but its block"
                                + " entity's script has no export render(), so there is nothing to"
                                + " draw with and the block renders as nothing. Either add an"
                                + " export render() that calls draw_box to the block entity's"
                                + " script, or drop the set_custom_render() call.");
            }
        } else if (boxes.isEmpty()) {
            GrugModTreeDefect.report(
                    "set_custom_render: the block declared custom rendering, but its block entity's"
                            + " render() drew no geometry, so the block renders as nothing. Either"
                            + " add a render() that calls draw_box, or drop the set_custom_render()"
                            + " call.");
        }
    }
}
