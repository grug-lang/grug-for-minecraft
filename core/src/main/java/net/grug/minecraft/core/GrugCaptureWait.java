package net.grug.minecraft.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Waits for the frame that serves a capture, with a deadline, and says why none came.
 *
 * <p>A loader that serves a capture from its frame hook holds the asking thread on a latch until a
 * frame ends that may be read. The hold has to end on its own: a frame serves only when it is drawn
 * after the ask and is without the integrated server's save indicator, so a client that stops
 * drawing, or one that keeps saving, would otherwise hold the test's thread and the whole CI job
 * until the run's timeout kills them with no test named.
 *
 * <p>The answer is a message rather than an exception: the caller reports it as a host function
 * error on the thread that asked, where it fails that test's call and names the test, and keeps
 * this wait free of the game and of the grug state. A null answer means a frame served the capture.
 *
 * <p>An interrupted wait answers the same way and puts the interrupt back. A CI run is not
 * interrupted, so that arm describes a shutdown that cut a capture short, and it must fail the test
 * rather than look like a capture that never had to happen.
 *
 * <p>Pure Java and game-free, so its test drives all three arms without a client: a latch that is
 * counted down, one that never is, and one awaited by an interrupted thread.
 */
public final class GrugCaptureWait {
    /**
     * How long a capture waits for its frame: three times the runner's default budget of 200 ticks,
     * which is 10 seconds at 20 ticks per second.
     *
     * <p>A test's budget of ticks does not advance while the wait holds its thread, so the budget
     * is the measure of how long a run expects a test like this to take, and a frame that needs
     * longer than three of them is a client that stopped rather than a slow renderer. The limit
     * also stays under the grug state lock's 60-second gate, so a wait that has to give up fails
     * its test rather than being reported as a defect in the lock.
     */
    public static final long TIMEOUT_MILLIS = 30_000;

    private GrugCaptureWait() {}

    /**
     * Waits for the frame that serves a capture, and answers why none came, or null when one did.
     *
     * <p>Returns once a frame served the capture, or after {@link #TIMEOUT_MILLIS}, or when the
     * thread is interrupted. See {@link #await(CountDownLatch, long)}.
     */
    public static String await(CountDownLatch served) {
        return await(served, TIMEOUT_MILLIS);
    }

    /**
     * Waits up to {@code timeoutMillis} for the frame that serves a capture, and answers why none
     * came, or null when one did.
     *
     * <p>The message names the wait and the condition the frame has to meet, because a capture that
     * fails without saying what it was waiting for leaves a test author guessing at the world, the
     * camera or the save indicator.
     */
    static String await(CountDownLatch served, long timeoutMillis) {
        boolean arrived;
        try {
            arrived = served.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Screenshot.capture: the wait for the frame the capture reads was interrupted,"
                    + " so no frame was served.";
        }
        if (arrived) {
            return null;
        }
        return "Screenshot.capture: the wait for the frame the capture reads ended after "
                + timeoutMillis
                + " ms without one. It waits for the first frame drawn after the ask that is"
                + " without the integrated server's save indicator, so no frame was drawn at all,"
                + " or every frame that was had the text.";
    }
}
