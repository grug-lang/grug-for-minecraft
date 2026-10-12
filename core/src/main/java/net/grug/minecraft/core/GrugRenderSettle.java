package net.grug.minecraft.core;

/**
 * Whether the frame a capture would read is one the client still had work in.
 *
 * <p>A capture reads the frame the client drew last, so "the renderer has no pending rebuilds" is
 * not on its own enough: the renderer can go idle between two frames (on 1.20.6 a compile finishing
 * on a worker thread empties its queues; on the older loaders a window resize or the world load
 * leaves the frame before it at the wrong size or with the fixture missing), and a check made on a
 * later game tick would then pass while the frame the capture reads is still the older one. The
 * game draws fewer frames than it runs ticks, so a tick-based answer cannot see that difference.
 *
 * <p>The loader therefore calls {@link #onFrame} from its own frame hook, with whether the frame
 * that just finished still had work in it, and a screenshot test waits for {@link #settled}.
 * Sampling at the end of the frame is what makes the answer describe the frame a capture would
 * read: a frame that drained the work it had is one the capture may read, and a frame that did not
 * is one it may not.
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
     * @param frameHadPendingWork whether that frame still had work in it: rebuilds it did not get
     *     to, light it had not propagated, or a window that was not at the size a capture reads.
     */
    public synchronized void onFrame(boolean frameHadPendingWork) {
        frames++;
        if (frameHadPendingWork) {
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
