package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugScreenshots;

/**
 * Whether the frame a capture would read is one the client had no work left in.
 *
 * <p>A capture reads the frame the client drew last, so "the renderer has no pending rebuilds" is
 * not on its own enough: the renderer can go idle between two frames (on 1.20.6 a compile finishing
 * on a worker thread empties its queues; on the older loaders a window resize or the world load
 * leaves the frame before it at the wrong size or with the fixture missing), and a check made on a
 * later game tick would then pass while the frame the capture reads is still the older one. The
 * game draws fewer frames than it runs ticks, so a tick-based answer cannot see that difference.
 *
 * <p>The loader therefore calls {@link #onFrame} from its own frame hook, with whether the frame
 * that just finished still had work in it and whether the client still owed a test's block edit,
 * and a screenshot test waits for {@link #settled}. Sampling at the end of the frame is what makes
 * the answer describe the frame a capture would read: a frame that drained the work it had is one
 * the capture may read, and a frame that did not is one it may not.
 *
 * <p>The two calls can arrive on different threads: a capture's wait runs on the render thread
 * while a test's own wait can run on the game-tick thread. Both methods are synchronized because a
 * torn read of the two counters could let a capture through one frame early.
 */
public final class GrugRenderSettle {
    /** Frames drawn so far. */
    private long frames;

    /** The value {@link #frames} had when the frame that finished last had work in it. */
    private long lastBusyFrame;

    /**
     * One completed frame, called from the loader's own frame hook.
     *
     * <p>Both facts are passed in rather than combined by the loader, and their disjunction is
     * here, so no loader carries a branch that only a frame drawn while a test edit was owed meets.
     * A frame drawn before a test's block edit reached the client is one a capture must not read,
     * so it counts as busy like a frame that still had work in it. See #804.
     *
     * @param frameHadPendingWork whether that frame still had work in it: rebuilds it did not get
     *     to, light it had not propagated, or a window that was not at the size a capture reads.
     * @param blocksPending whether the client was still owed a block a test had placed.
     */
    public synchronized void onFrame(boolean frameHadPendingWork, boolean blocksPending) {
        frames++;
        if (frameHadPendingWork || blocksPending) {
            lastBusyFrame = frames;
        }
    }

    /**
     * Whether the frame a capture would read had nothing pending, so the capture would read the
     * settled scene.
     *
     * <p>The frame a capture reads is one behind the last one drawn: on this stack the back buffer
     * it reads holds what the previous swap left there, which is the frame before the last. So the
     * last two frames have to have ended idle for the one the capture reads to have. False before
     * two frames have been drawn, when there is nothing settled to read yet.
     */
    public synchronized boolean settled() {
        return frames - lastBusyFrame >= 2;
    }

    /**
     * Notes that work for the renderer has just been made, which the next frames have not seen yet,
     * so the frame a capture would read is not settled until two frames after it have ended idle.
     *
     * <p>A frame's own sample describes the work that frame found, and a change made after it is
     * invisible to it. The game runs up to 10 ticks for one frame and the runner runs every test at
     * that speed, so a test can make a change and ask whether the world is settled before any frame
     * has been drawn since, and the answer from the frames before the change would let a capture
     * through that reads the scene without it.
     */
    public synchronized void noteBusy() {
        lastBusyFrame = frames;
    }

    /**
     * {@link #settled}, also asking the renderer and the client's copy of a test's blocks now. A
     * change that left rebuilds pending is not settled however idle the last frames were, and the
     * frames after it are the ones that count. An edit the client has not shown yet is the same:
     * every frame counted so far describes a world without it.
     *
     * <p>The block answer is passed in rather than combined by the loader, so no loader carries a
     * branch that only a poll landing while an edit is owed meets. It does not call {@link
     * #noteBusy} itself: the frame hook already counts the frame an edit's arrival lands in, and
     * only a frame can say the client has it. See #804.
     *
     * @param workPendingNow whether the renderer has work for a frame that has not been drawn yet,
     *     which the loader reads at the moment of the question.
     * @param blocksIdle whether the client holds every block a test has placed.
     */
    public synchronized boolean settledNow(boolean workPendingNow, boolean blocksIdle) {
        if (workPendingNow) {
            noteBusy();
            return false;
        }
        return blocksIdle && settled();
    }

    /**
     * Whether the client has nothing left for the frame a capture would read, given what the loader
     * reads at the moment of the question: the light engine's queues, the client's queue of
     * received light packets, the frames drawn, and the client's copy of the blocks a test placed.
     *
     * <p>Pure, so that its unit test covers every combination of the four answers, instead of each
     * loader carrying a conjunction whose branch a run only meets when a poll lands at the right
     * moment. A capture taken while any of them is busy reads a frame from before a change. See
     * #424 and #804.
     */
    public static boolean clientSettled(
            boolean engineHasLightWork,
            boolean updateQueueEmpty,
            boolean framesIdle,
            boolean blocksIdle) {
        return lightSettled(engineHasLightWork, updateQueueEmpty) && framesIdle && blocksIdle;
    }

    /**
     * Whether the client has no light work left, given the two places it can be waiting: the light
     * engine's own queues, and the separate queue of light packets the client has received and not
     * applied yet (1.20.6's {@code ClientLevel.isLightUpdateQueueEmpty}, which drains at a capped
     * rate). Both are read by the loader and passed in, so that the rule is a pure function that a
     * unit test covers in every combination, instead of a branch that only a poll landing at the
     * right moment meets. A capture taken while either holds work would read a frame lit with light
     * that is still queued. See #424.
     */
    public static boolean lightSettled(boolean engineHasLightWork, boolean updateQueueEmpty) {
        return !engineHasLightWork && updateQueueEmpty;
    }

    /**
     * Whether the integrated server has finished the light a rebuild asked for, given the three
     * places it can be waiting: the light engine's own propagation queues, the engine's list of
     * block and section checks it has taken but not run yet (1.20.6's {@code
     * ThreadedLevelLightEngine.lightTasks}, drained in batches of a thousand, with its own
     * propagation run only between batches), and that engine's flag saying a batch is already
     * scheduled on the light thread.
     *
     * <p>The engine's public {@code hasLightWork} counts only the first, so it answers idle while
     * the checks a batch has not reached yet, or the batch itself, are still queued. A client is
     * idle while the server has not computed the light at all, so a settle answer built on that
     * alone describes a world whose room has not been relit yet. See #686.
     *
     * <p>The values are passed in rather than combined by the loader, so the conjunction is a pure
     * function its unit test covers in every combination, instead of a loader carrying a
     * conjunction whose arms a run only meets when the light thread happens to be between batches.
     *
     * @param engineHasLightWork whether the light engine's propagation queues hold work
     * @param tasksQueued whether the engine has taken checks or batch runs it has not run yet
     * @param updateScheduled whether a batch is already scheduled on the light thread
     */
    public static boolean serverLightSettled(
            boolean engineHasLightWork, boolean tasksQueued, boolean updateScheduled) {
        return !engineHasLightWork && !tasksQueued && !updateScheduled;
    }

    /**
     * Whether the frame that just finished still had work in it: it was drawn before the window
     * reached the size a capture reads, before the renderer or the player existed, or with a chunk
     * rebuild still owed.
     *
     * <p>The values are passed in rather than compared by the loader, so the conjunction is a pure
     * function its unit test covers in every combination, instead of a loader carrying guards whose
     * wrong-window or missing-player arm no run reaches. See #257 and #804.
     *
     * @param displayWidth the window width the frame was drawn at
     * @param displayHeight the window height the frame was drawn at
     * @param renderGlobal the client's renderer, or null before its world exists
     * @param player the client's player, or null before its world exists
     * @param chunkPending whether a chunk in view still owed a rebuild
     */
    public static boolean frameHadPendingWork(
            int displayWidth,
            int displayHeight,
            Object renderGlobal,
            Object player,
            boolean chunkPending) {
        return displayWidth != GrugScreenshots.WIDTH
                || displayHeight != GrugScreenshots.HEIGHT
                || renderGlobal == null
                || player == null
                || chunkPending;
    }

    /**
     * Forgets every frame counted so far, so that the next answer can only be about frames drawn
     * after this call.
     *
     * <p>The runner builds a test's room in the same tick as that test's first call, and a repeat
     * runs the whole suite again in one client. Without this, a test that follows an idle one would
     * read a settled answer left over from before its own setup, and capture the frame before its
     * fixture was drawn.
     */
    public synchronized void reset() {
        frames = 0;
        lastBusyFrame = 0;
    }
}
