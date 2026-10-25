package net.grug.minecraft.grug;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs a block entity's render pass and hands back the geometry it drew.
 *
 * <p>The pass belongs to the loader, because the geometry has to land in whatever the loader's
 * rendering stack is in the middle of building and only the loader knows what that is. What is here
 * is the game-independent half of it: open the pass, call the render function, close it, and report
 * a pass that produced nothing to draw.
 */
public final class GrugBlockGeometry {

    /**
     * The blocks already reported, so a player hears about a broken one once rather than on every
     * chunk rebuild. Keyed on the same description the report carries, which names the block and
     * where it is, so two different broken blocks are two lines and one broken block is one line
     * however often the game redraws it.
     *
     * <p>Never cleared, which is a trade and not a free win. The cost is real: in one session a
     * block can be fixed, break again, and render as nothing with nothing said. That is accepted
     * because the alternative is a report on every chunk rebuild for as long as the player keeps
     * the block in view, and a run has already ended at the first one anyway, since {@code
     * run-loader.sh} fails on the first {@code FAIL} line and stops the client. The audience for a
     * repeat is a player, not a test run.
     */
    private static final Set<String> REPORTED_BLOCKS = new HashSet<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugBlockGeometry() {}

    /**
     * Draws {@code entityHandle}'s frame render function and returns the stacks it drew.
     *
     * <p>A frame pass runs every frame the block is in view, so it reports nothing when it drew
     * nothing: a machine with no item in flight has nothing to draw, and that is not a defect. The
     * partial tick and the camera direction are the loader's, not the script's, because only the
     * loader knows how much of the frame's tick has elapsed and where its camera looks.
     *
     * <p>The pass runs under the state lock for the same reason as {@link #draw(long, long,
     * String)}: two concurrent passes would interleave the static bookkeeping and draw one block's
     * stacks into another's frame.
     *
     * @param partialTick how much of the current tick has already elapsed, from 0 up to but not
     *     including 1
     * @param cameraYaw the direction the camera looks in, in degrees
     */
    public static GrugFrameGeometry drawFrame(
            long entityHandle, long frameRenderFnId, double partialTick, double cameraYaw) {
        GrugStateLock.lock();
        try {
            GrugRenderPass.openFrame(partialTick, cameraYaw);
            GrugFrameGeometry geometry;
            try {
                if (entityHandle != 0) {
                    callExport(entityHandle, frameRenderFnId, GrugRenderPass.context());
                }
            } finally {
                geometry = GrugRenderPass.closeFrame();
            }
            return geometry;
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Draws {@code entityHandle}'s render function and returns the boxes it drew.
     *
     * @param blockDescription what to call this block in a report, which is how its author finds it
     * @return the boxes and model faces the render function drew, in the order it drew them
     *     <p>Reports, rather than throws, when there is nothing to draw. A block that declared
     *     custom rendering can end up with nothing to draw for two separate reasons, and each names
     *     the call that would fix it. Both are reported, because a block that renders as nothing is
     *     not a shape anyone chose and silence would let it ship. The sandbox is that the script
     *     carries on afterwards.
     *     <p>Neither report can go through {@link Grug#hostFunctionErrorHappened}. That reports
     *     from inside a grug call, and by here the render function has returned and grug has
     *     already popped the frame it pushed, so the call stack this would report against is empty
     *     and grug aborts the game rather than failing the run. {@link GrugModTreeDefect} is the
     *     reporting channel that works from outside a call.
     */
    public static GrugGeometry draw(long entityHandle, long renderFnId, String blockDescription) {
        // The whole pass runs under the state lock, because 1.20.6 rebuilds chunks on worker
        // threads and two concurrent passes would interleave open/record/close on the static
        // state, cross-contaminating one block's boxes into another's geometry. The lock is
        // reentrant, so the callExportFn inside callRender and any host function the render
        // function calls acquire it again on the same thread without deadlocking. This also
        // protects REPORTED_BLOCKS below, which is mutated from the same call path.
        GrugStateLock.lock();
        try {
            GrugRenderPass.open();

            // The pass closes even when the render call does not come back, because callExportFn
            // rethrows a fatal raised inside the script once the native call returns to Java. A
            // pass left open would make the next block's open() report a nesting invariant rather
            // than the defect that actually happened. The fatal still propagates: this is about
            // which message the player reads, not about swallowing anything.
            boolean ranRender;
            GrugGeometry geometry;
            try {
                // A block that declared custom rendering without a block entity script has no
                // entity to run a render function on, and passing the zero handle on would hand the
                // native side a null entity pointer.
                ranRender = false;
                if (entityHandle != 0) {
                    ranRender = callExport(entityHandle, renderFnId, GrugRenderPass.context());
                }
            } finally {
                geometry = GrugRenderPass.close();
            }

            // Behind a method of its own, so that what is excluded is the choice between the
            // reports and not the pass bookkeeping around it, which every run reaches.
            reportNoGeometry(entityHandle, ranRender, geometry, blockDescription);

            return geometry;
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Calls an engine-called render export with the pass's context, and answers whether it called
     * it.
     *
     * <p>The invalid-id check lives in here rather than in the caller because it cannot be false
     * today: grug builds its table of export ids from {@code mod_api.json}, so the id for {@code
     * render} resolves whatever any script exports. It stays as a guard rather than being dropped,
     * because the engine indexes that table with the id, so passing an invalid one indexes it out
     * of bounds and panics, and that would turn a future edit to {@code mod_api.json} into a crash
     * rather than a report.
     *
     * <p>The context is a grug entity of its own for exactly the duration of the call, so a script
     * that saves it in a member variable holds an entity that no longer exists, and the context's
     * methods refuse it. It is destroyed in the finally so a fatal raised inside the script cannot
     * leave it behind.
     *
     * <p>The method as a whole is excluded because the call needs a live grug entity, which only a
     * game has. The guard on the handle is in front of it, in {@link #draw}, and stays measured.
     */
    @GrugGenerated(
            "the call into the VM, its context entity, and the guard on an id that cannot be"
                    + " invalid while mod_api.json declares render and render_frame: exercising"
                    + " either needs a live grug entity")
    private static boolean callExport(
            long entityHandle, long exportFnId, GrugRenderPass.Context context) {
        if (exportFnId == Grug.INVALID_GRUG_EXPORT_FN_ID) {
            return false;
        }

        long contextHandle = GrugEntities.addEntity(GrugEntityType.RenderContext, context);
        try {
            Grug.callExportFn(entityHandle, exportFnId, contextHandle);
        } finally {
            GrugEntities.removeEntity(contextHandle);
        }
        return true;
    }

    /**
     * Reports the mistake that applies, if either does.
     *
     * <p>Both ways of arriving here need care in how they are worded, because one of them is a call
     * the author has already made and one is not:
     *
     * <ul>
     *   <li>No block entity at all, so {@code set_block_entity} is the fix.
     *   <li>A block entity whose script exports no {@code render()}. The engine declines that call
     *       without reporting, so it arrives here as a pass that ran and drew nothing, and the
     *       message names both possibilities: add the export, or, if it is there, make it call
     *       {@code draw_box}. It cannot say which, because nothing in the API asks whether this one
     *       script has a given export: grug builds its table of export ids from {@code
     *       mod_api.json} rather than from the files that are loaded, so the id for {@code render}
     *       resolves whatever any script exports.
     * </ul>
     *
     * <p>Excluded because the second arm needs a pass that ran, which means a live grug entity,
     * which only a game has. The arm a Java test can reach is the one for a block with no block
     * entity at all.
     */
    @GrugGenerated(
            "the report for a pass that ran and drew nothing: it needs a live grug entity to have"
                    + " run, so only a game reaches it; the no-block-entity arm is what"
                    + " GrugBlockGeometryTest covers")
    private static void reportNoGeometry(
            long entityHandle, boolean ranRender, GrugGeometry geometry, String blockDescription) {
        String report = null;
        if (!ranRender) {
            report =
                    "set_custom_render: "
                            + blockDescription
                            + " declared custom rendering, but it has no block entity, so there is"
                            + " no render() to draw with and the block renders as nothing. Either"
                            + " add the block entity with set_block_entity, or drop the"
                            + " set_custom_render() call.";
        } else if (geometry.isEmpty()) {
            // This cannot say which of four things happened, because it cannot tell them apart:
            // render() may be absent, may never call draw_box, may have every box refused, or may
            // have stopped early on another host function's error. The last two have already been
            // reported by the thing that refused or failed, and those say precisely which, so the
            // wording points at them rather than guessing. GrugRenderPass does not count refusals
            // and callRender does not read the result the native call gives it, so naming one cause
            // here would be naming a cause this report cannot distinguish from the other three.
            report =
                    "set_custom_render: "
                            + blockDescription
                            + " declared custom rendering, but its block entity's render() drew no"
                            + " geometry, so the block renders as nothing. If a draw_box,"
                            + " draw_model or other error was reported for it above, that says"
                            + " which of these it was and that is the one to fix: everything it"
                            + " drew was refused, or render() stopped early. Otherwise add an"
                            + " export render() to the block entity's script that calls draw_box or"
                            + " draw_model, or drop the set_custom_render() call.";
        }

        // Once per block rather than once per chunk compile. The game redraws a broken block every
        // time its chunk is rebuilt and every loader drains this queue into chat, so without this a
        // machine that renders as nothing would say so again on every rebuild, for as long as the
        // player kept it in view.
        if (report != null && REPORTED_BLOCKS.add(blockDescription)) {
            GrugModTreeDefect.report(report);
        }
    }
}
