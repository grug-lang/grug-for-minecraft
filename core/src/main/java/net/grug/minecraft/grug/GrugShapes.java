package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * block that never called {@code set_shape} costs one map lookup after its first query, because the
 * only call into the VM it ever makes is the one that checks it has no shape export to run.
 */
public final class GrugShapes {
    /**
     * The context one {@code shape()} call was passed, which is what the context's methods check
     * before they record a box. It holds the boxes that call declared, in its own lists, so a call
     * that nested inside another one would record into its own and could not wipe the outer call's.
     * A new one is made for every call and cleared when it returns, so a context saved in a member
     * variable and used later is refused.
     *
     * <p>Only one is open at a time, because the whole call runs under the state lock and no host
     * function starts a shape call. If one ever did, the nested call's context would replace the
     * outer one here, so the outer call's boxes recorded up to that point would survive and its
     * later ones would be refused with a report rather than mixed into the nested call's shape.
     */
    public static final class Context {
        private final List<GrugBox> collision = new ArrayList<>();
        private final List<GrugBox> selection = new ArrayList<>();

        @GrugGenerated("marker: only a shape call makes one")
        private Context() {}

        /** The boxes entities collide with, in the order the call recorded them. */
        List<GrugBox> collision() {
            return collision;
        }

        /** The boxes the game targets and outlines, or an empty list to use the collision ones. */
        List<GrugBox> selection() {
            return selection;
        }
    }

    /**
     * What one {@code shape()} call did: whether its export ran, whether the script aborted from an
     * error of its own, and the boxes it declared.
     *
     * <p>{@link #call} answers null instead when the block's script made no entity to run the call
     * on, which is the one outcome that has neither an export answer nor any boxes.
     */
    static final class Call {
        final boolean ran;
        final boolean errored;
        final List<GrugBox> collision;
        final List<GrugBox> selection;

        Call(boolean ran, boolean errored, List<GrugBox> collision, List<GrugBox> selection) {
            this.ran = ran;
            this.errored = errored;
            this.collision = collision;
            this.selection = selection;
        }
    }

    /** The context of the {@code shape()} call that is open right now, or null between calls. */
    private static Context context = null;

    /**
     * The blocks whose shape export has already been asked for, whether or not it ran, so a block
     * that never called {@code set_shape} enters the VM once per run rather than on every collision
     * query the game makes for it.
     *
     * <p>Concurrent rather than guarded by the state lock, because both game threads reach {@link
     * #shape} and this is written before the call that takes that lock. The rule is one atomic
     * check-and-add, which is what {@code add} answers, so nothing else has to move with it.
     */
    private static final Set<Long> PROBED_BLOCKS = ConcurrentHashMap.newKeySet();

    /**
     * The blocks already reported, keyed on the same description the report carries, so a mod
     * author hears about a broken block once rather than on every collision query. Never cleared,
     * for the reason {@link GrugBlockGeometry} gives: the run has already ended at the first
     * report.
     *
     * <p>Concurrent for the reason {@link #PROBED_BLOCKS} gives: both threads add here after the
     * shape call that held the lock has returned, and only the one whose add answers true reports,
     * so a defect reaches the player once however the two interleave.
     */
    private static final Set<String> REPORTED_BLOCKS = ConcurrentHashMap.newKeySet();

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
        return record(box, true);
    }

    /**
     * Records one selection box.
     *
     * @return false as {@link #recordCollision} does.
     */
    public static boolean recordSelection(GrugBox box) {
        return record(box, false);
    }

    private static boolean record(GrugBox box, boolean collision) {
        if (context == null || !GrugRenderPass.isOrdered(box)) {
            return false;
        }
        List<GrugBox> boxes = collision ? context.collision() : context.selection();
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
        if (data == null) {
            return null;
        }

        String description = data.id + " at " + x + ", " + y + ", " + z;
        if (!data.hasShape) {
            // The game only asks a block that called set_shape, so a block that exports shape()
            // without declaring it would have its export run never and be a silent full cube. Ask
            // it once, on the first query the game makes for it, and report the mismatch.
            if (probeOnce(blockFileId)) {
                reportUndeclaredShape(call(blockFileId, world, x, y, z), description);
            }
            return null;
        }

        return decide(call(blockFileId, world, x, y, z), description);
    }

    /**
     * Spends {@code blockFileId}'s one probe this run, answering whether this call is the one that
     * got it.
     *
     * <p>Both game threads ask, so the membership check and the insert are one atomic operation of
     * the set rather than a {@code contains} followed by an {@code add}: only the call that answers
     * true runs the export and reports the mismatch, so a block that exports {@code shape()}
     * without declaring it is entered once and named once however the two threads interleave.
     */
    static boolean probeOnce(long blockFileId) {
        return PROBED_BLOCKS.add(blockFileId);
    }

    /**
     * Runs one {@code shape()} call on the block's own entity and answers what it did, or null when
     * the block's script made no entity to run it on.
     *
     * <p>Excluded because creating the entity is a native call, and the one answer that says it
     * could not create one cannot happen for a block whose script compiled: only a broken engine
     * state reaches it, and keeping the full cube is the right answer there rather than handing the
     * VM a null entity. The call through the entity is measured by {@code block_shape-Test.grug},
     * which reaches it on every loader.
     */
    @GrugGenerated(
            "creating the block's entity for one shape call, and the answer that it made none: a"
                    + " compiled block always makes one, so only a broken engine state keeps the"
                    + " full cube")
    private static Call call(long blockFileId, Object world, int x, int y, int z) {
        GrugStateLock.lock();
        try {
            long blockHandle = Grug.createEntity(blockFileId);
            if (blockHandle == 0) {
                return null;
            }
            return callScript(blockHandle, world, x, y, z);
        } finally {
            GrugStateLock.unlock();
        }
    }

    /**
     * Calls the block's {@code shape()} export once, with its context and world entities, and hands
     * back what it did and declared. The caller holds the state lock.
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
    private static Call callScript(long blockHandle, Object world, int x, int y, int z) {
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

        boolean errored = GrugTestState.runtimeErrorCount != errorsBefore;
        return new Call(ran[0], errored, openContext.collision(), openContext.selection());
    }

    /**
     * The rule that turns what one call did into the shape the game uses, or the report that says
     * why there is none.
     *
     * <p>The two reports below fail the run, and a run stops at its first {@code FAIL} line, so no
     * grug test can reach either of them. The rule is its own method so that a Java test can drive
     * every combination of its inputs and read the exact message each one reports, which is what
     * {@code GrugShapesTest} does, rather than leaving the reports to an exclusion.
     *
     * @param call the call's outcome, or null when the block's script made no entity to run it on
     * @return the boxes to use, or null to keep the block's full cube
     */
    static GrugShape decide(Call call, String description) {
        if (call == null) {
            return null;
        }

        if (!call.ran && !call.errored) {
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

        if (call.collision.isEmpty() && call.selection.isEmpty()) {
            if (!call.errored && REPORTED_BLOCKS.add(description)) {
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

        return new GrugShape(call.collision, call.selection);
    }

    /**
     * Reports the other half of the declaration rule: a block that exports {@code shape()} without
     * calling {@code set_shape()} in its {@code init()}, whose export the game therefore never
     * runs.
     *
     * <p>Reached from the first query the game makes for the block, because that is the only moment
     * a script's export can be asked whether it exists at all: grug's export table is built from
     * {@code mod_api.json}, so the id for {@code shape} resolves for every script and only calling
     * it says whether this one defines it. It is a rule of its own so a Java test can drive it, for
     * the reason {@link #decide} gives.
     */
    static void reportUndeclaredShape(Call call, String description) {
        if (call == null || !call.ran || !REPORTED_BLOCKS.add(description)) {
            return;
        }

        GrugModTreeDefect.report(
                "set_shape: "
                        + description
                        + " exports a shape() function, but its init() never called set_shape(),"
                        + " so the game never asks for its boxes and keeps the block's full cube."
                        + " Add ctx.set_shape() to init(), or remove the export.");
    }
}
