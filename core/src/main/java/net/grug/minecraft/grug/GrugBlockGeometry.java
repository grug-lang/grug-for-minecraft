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
     * with no geometry at all: a block entity whose script has no render function, and one whose
     * render function draws nothing. Both are reported here, because a block that renders as
     * nothing is not a shape anyone chose and silence would let it ship. The sandbox is that the
     * script carries on afterwards.
     */
    @GrugGenerated(
            "no geometry: only a mod that declares set_custom_render without a render function, or"
                    + " with one that draws nothing, reaches this, and such a mod fails the run it"
                    + " is loaded into")
    public static List<GrugBox> draw(long entityHandle, long renderFnId) {
        GrugRenderPass.open();

        if (renderFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            Grug.callExportFn(entityHandle, renderFnId);
        }

        List<GrugBox> boxes = GrugRenderPass.close();

        if (boxes.isEmpty()) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "set_custom_render: the block declared custom rendering, but its block entity's"
                            + " render() drew no geometry, so the block renders as nothing. Either"
                            + " add a render() that calls draw_box, or drop the set_custom_render()"
                            + " call.");
        }

        return boxes;
    }
}
