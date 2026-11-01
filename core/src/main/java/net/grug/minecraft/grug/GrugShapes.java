package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Asks a block's {@code shape} export for the boxes the game uses at one position.
 *
 * <p>This is the game-independent half of a block's shape, like {@link GrugBlockGeometry} for its
 * geometry: open a context, call the script, close the context, and hand back what it declared. The
 * loader turns the boxes into whatever its version collides and outlines with, and only the loader
 * knows what that is.
 *
 * <p>The boxes are asked for on every query rather than cached here, because a shape can follow
 * what is around it: a pipe's arms follow the inventories and pipes beside it, and a shape that
 * followed its neighbours only after a chunk rebuild would collide differently from how it looks. A
 * block that never called {@code set_shape} costs one map lookup, because only a declared shape
 * enters the VM.
 */
public final class GrugShapes {
    /**
     * The context one {@code shape()} call was passed, which is what the context's methods check
     * before they record a box. A new one is made for every call and cleared when it returns, so a
     * context saved in a member variable and used later is refused.
     */
    public static final class Context {
        @GrugGenerated("marker: only a shape call makes one")
        private Context() {}
    }

    private static final List<GrugBox> COLLISION = new ArrayList<>();
    private static final List<GrugBox> SELECTION = new ArrayList<>();
    private static Context context = null;

    /**
     * The blocks already reported, keyed on the same description the report carries, so a mod
     * author hears about a broken block once rather than on every collision query. Never cleared,
     * for the reason {@link GrugBlockGeometry} gives: the run has already ended at the first
     * report.
     */
    private static final Set<String> REPORTED_BLOCKS = new HashSet<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugShapes() {}

    /** The context of the {@code shape()} call that is open right now, or null between calls. */
    public static Context context() {
        return context;
    }

    /** Opens the context of one {@code shape()} call. */
    static Context openContext() {
        context = new Context();
        return context;
    }

    /** Closes it again, so a saved context no longer matches. */
    static void closeContext() {
        context = null;
    }

    /**
     * Records one collision box.
     *
     * @return false when no shape call is open or the box's corners are the wrong way round. The
     *     caller reports that, so the message names the call that made it.
     */
    public static boolean recordCollision(GrugBox box) {
        return record(box, COLLISION);
    }

    /**
     * Records one selection box.
     *
     * @return false as {@link #recordCollision} does.
     */
    public static boolean recordSelection(GrugBox box) {
        return record(box, SELECTION);
    }

    private static boolean record(GrugBox box, List<GrugBox> boxes) {
        if (context == null || !GrugRenderPass.isOrdered(box)) {
            return false;
        }
        boxes.add(box);
        return true;
    }

    /**
     * Asks {@code blockFileId}'s script for its boxes at one position.
     *
     * @param world the loader's Level object, which is what the script reads its neighbours through
     * @return the boxes it declared, or null when the block never declared a shape or added nothing
     *     usable, in which case the game keeps the block's full cube and the author has been told
     */
    public static GrugShape shape(long blockFileId, Object world, int x, int y, int z) {
        GrugBlockData data = GrugDeclarations.blockDataByFileId.get(blockFileId);
        if (data == null || !data.hasShape) {
            return null;
        }

        String description = data.id + " at " + x + ", " + y + ", " + z;
        return ask(blockFileId, world, x, y, z, description);
    }

    /**
     * The call into the VM, its context and world entities, and the report for a shape that
     * declared nothing.
     *
     * <p>Excluded because every path in it needs a live grug entity, which only a game has. The
     * decision not to ask at all lives in {@link #shape}, and stays measured.
     *
     * <p>A script that aborts on an error of its own returns from the call the way a script with no
     * such export does, so the runtime error counter tells them apart: an aborted call has already
     * reported its error and is not reported again as a missing export, and the boxes it added
     * before aborting are used as they stand.
     *
     * <p>The whole call runs under the state lock, for the reason {@link GrugBlockGeometry#draw}
     * gives: the root list a fresh owner swaps in is process-global state that another thread's
     * entry into grug would otherwise read and root its own entities in. The script's own
     * neighbours are asked through block entity host functions, so this call reaches into other
     * scripts, and it has to be one call at a time like every other.
     */
    @GrugGenerated(
            "the call into the VM and its context and world entities: exercising any of them needs"
                    + " a live grug entity")
    private static GrugShape ask(
            long blockFileId, Object world, int x, int y, int z, String description) {
        GrugStateLock.lock();
        try {
            long blockHandle = Grug.createEntity(blockFileId);
            if (blockHandle == 0) {
                return null;
            }

            COLLISION.clear();
            SELECTION.clear();
            Context openContext = openContext();
            long contextHandle = GrugEntities.addEntity(GrugEntityType.ShapeContext, openContext);
            long worldId = GrugEntities.addEntity(GrugEntityType.Level, world);
            boolean[] ran = new boolean[1];
            int errorsBefore = GrugTestState.runtimeErrorCount;
            try {
                GrugEntities.withFreshOwner(
                        () ->
                                ran[0] =
                                        ExportFns.Block_shape(
                                                blockHandle, contextHandle, worldId, x, y, z));
            } finally {
                GrugEntities.removeEntity(worldId);
                GrugEntities.removeEntity(contextHandle);
                closeContext();
                Grug.destroyEntity(blockHandle);
            }

            // The engine answers a script that aborted from its own error the same way it answers
            // one with no such export, so the error counter is what tells the two apart. An aborted
            // call has already reported its own error, and the boxes it added before aborting are
            // the shape, or none is.
            boolean errored = GrugTestState.runtimeErrorCount != errorsBefore;

            if (!ran[0] && !errored) {
                if (REPORTED_BLOCKS.add(description)) {
                    GrugModTreeDefect.report(
                            "set_shape: "
                                    + description
                                    + " declared a shape, but its block script exports no"
                                    + " shape(), so the game keeps the block's full cube. Add an"
                                    + " export shape(ctx: ShapeContext, world: Level, x: number, y:"
                                    + " number, z: number) that adds the boxes, or drop the"
                                    + " set_shape() call.");
                }
                return null;
            }

            if (COLLISION.isEmpty() && SELECTION.isEmpty()) {
                if (!errored && REPORTED_BLOCKS.add(description)) {
                    GrugModTreeDefect.report(
                            "shape: "
                                    + description
                                    + " added no box, so the block would have nothing to"
                                    + " collide with or to click, and the game keeps its full cube"
                                    + " instead. Add at least a selection box with"
                                    + " add_selection_box(), and a collision box with"
                                    + " add_collision_box() if entities should not pass through"
                                    + " it.");
                }
                return null;
            }

            return new GrugShape(COLLISION, SELECTION);
        } finally {
            GrugStateLock.unlock();
        }
    }
}
