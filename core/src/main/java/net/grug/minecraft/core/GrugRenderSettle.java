package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;

import java.util.function.BooleanSupplier;

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
 *
 * <p>The class also decides what a frame may contain rather than what work it had left: the
 * integrated server's world-save indicator is drawn into the frames after a save ended, and {@link
 * #awaitSavingIndicatorCleared} is what keeps a capture from reading it (#1132).
 */
public final class GrugRenderSettle {
    /** Frames drawn so far. */
    private long frames;

    /** The value {@link #frames} had when the frame that finished last had work in it. */
    private long lastBusyFrame;

    /**
     * The value {@link #frames} had when the frame that finished last had the save indicator in it.
     *
     * <p>Starts two frames back, so that a client that never drew the indicator answers cleared
     * before its first frame: there is nothing in the buffer to avoid.
     */
    private long lastIndicatorFrame = -2;

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
     * The alpha at or below which 1.20.6's {@code Gui.renderSavingIndicator} stops drawing the save
     * text: it draws only while the alpha it computes, a whole number from 0 to 255, is above 8.
     */
    private static final int SAVING_INDICATOR_ALPHA_FLOOR = 8;

    /**
     * Whether a frame drawn now would have the integrated server's "Saving world" text in it, from
     * the two values 1.20.6 fades that text with and whether the player has the option on.
     *
     * <p>{@code Gui.tickAutosaveIndicator} walks {@code autosaveIndicatorValue} a fifth of the way
     * toward 1 while {@code MinecraftServer.isCurrentlySaving()} is true and a fifth of the way
     * back afterward, and {@code renderSavingIndicator} draws the text in the bottom right of the
     * frame at that value's alpha. A save is over in a moment, but the value it raised is not: the
     * text stays in the frames for about ten client ticks after the save ended, which is long after
     * the server thread a test captures on has moved on. A capture of a still scene can therefore
     * read the text while the same scene without it is the golden, which is how {@code
     * coverage_flat_hand} flaked on 1.20.6-forge (#1132).
     *
     * <p>The alpha the renderer uses is interpolated between the two values with the frame's
     * partial time, so the strongest it can be is the larger of them. The answer is deliberately
     * conservative: it can be true for the tick before the text stops being drawn, which costs a
     * wait, and is never false while the text could still be drawn.
     *
     * <p>Pure, so its unit test covers every combination instead of the loader carrying a
     * comparison whose true arm a run only meets when an autosave happens to fall in a capture. The
     * same shape as {@link #lightSettled} and {@link #clientSettled}. See #424, #804 and #1132.
     *
     * @param indicatorEnabled whether the player's "show autosave indicator" option is on
     * @param value the indicator value the client last ticked to, 0 to 1
     * @param lastValue the value the tick before it left, 0 to 1
     */
    public static boolean savingIndicatorShown(
            boolean indicatorEnabled, float value, float lastValue) {
        if (!indicatorEnabled) {
            return false;
        }
        float strongest = Math.min(Math.max(value, lastValue), 1.0F);
        return (int) (255.0F * strongest) > SAVING_INDICATOR_ALPHA_FLOOR;
    }

    /**
     * Notes whether the frame that just finished had the integrated server's save indicator drawn
     * into it, from {@link #savingIndicatorShown} sampled at the same moment.
     *
     * <p>Called right after {@link #onFrame}, by a loader whose client draws such an indicator, so
     * that this counts the same frame. A loader whose client has none never calls it, and its
     * frames leave the state cleared.
     *
     * <p>Pure bookkeeping rather than a wait, so that the answer a capture needs is a frame number
     * and not a value read later: the indicator fades over about ten client ticks, and a value that
     * has just reached the floor says nothing about the frames already drawn while it was up. See
     * #1132.
     *
     * @param drawn whether that frame had the indicator in it
     */
    public synchronized void onSavingIndicatorFrame(boolean drawn) {
        if (drawn) {
            lastIndicatorFrame = frames;
        }
    }

    /**
     * Whether the frame a capture would read has no save indicator in it.
     *
     * <p>The frame a capture reads is one behind the last one drawn, so a frame drawn without the
     * indicator is not enough: the frames still on the buffer may be the ones that had it. Two
     * frames drawn after the last one with it make the read one of them, or of a later frame. True
     * before a frame with it is counted, so a capture waits for nothing when the client never
     * saved.
     */
    public synchronized boolean savingIndicatorCleared() {
        return frames - lastIndicatorFrame >= 2;
    }

    /**
     * Waits until the frame a capture would read has no save indicator in it.
     *
     * <p>A capture must not read a frame drawn while the integrated server's "Saving world" text
     * was up, and the text stays in the frames for about ten client ticks after a save ends. The
     * decision is {@link #savingIndicatorCleared}, which the loader samples once per frame; the
     * loop here is what a unit test covers, because the branch a run meets only when a capture
     * lands in a fade would otherwise go untested.
     *
     * <p>The answer can be false for the frames drawn before the client ticked the value back and
     * the renderer drew it, so the loop polls rather than sampling once.
     *
     * @param cleared whether the frame a capture would read was drawn without the indicator
     */
    public static void awaitSavingIndicatorCleared(BooleanSupplier cleared) {
        while (!cleared.getAsBoolean()) {
            pauseForSavingIndicator();
        }
    }

    /**
     * One millisecond of the save indicator wait. The frames it waits on arrive within a client
     * tick, so a millisecond is short enough that the wait ends within a frame of the state
     * changing, and long enough that it is not a spin.
     */
    @GrugGenerated("saving indicator wait: an interrupted wait cannot occur in a CI run")
    private static void pauseForSavingIndicator() {
        try {
            Thread.sleep(1L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        lastIndicatorFrame = -2;
    }
}
