package net.grug.minecraft.core;

/**
 * Whether the running test wants the client's next frame drawn.
 *
 * <p>A test that never captures reads state, not pixels, so the frames it does not ask for are pure
 * cost: the level draw, its chunk section rebuilds and the GUI, all of which #760 measured at 14 s
 * of the {@code 1.20.6-forge} test phase, 15% to 24% of it, with 71% of the run's frames belonging
 * to tests that never capture. The runner turns drawing off once a test's room is built and back on
 * when the test ends, and only the calls that need a drawn frame turn it on again: {@code
 * Test.screenshot}, a {@code Test.world_settled} wait, and either capture call.
 *
 * <p>Drawing is on outside a test, so the client draws its title screen, the world load and the
 * frames between two tests as it always did. Nothing here decides what a frame contains: a loader
 * cancels its own draw while this answers false, which is the one thing every loader does
 * differently.
 *
 * <p>Volatile because the runner ticks on the server thread and the loaders ask from the render
 * thread. The value can only change between two frames, since both are driven from one thread's
 * turn, so a torn answer is not a hazard; the writes only have to become visible.
 */
public final class GrugFrameRequest {
    /** On by default, so nothing outside a test changes what the client draws. */
    private static volatile boolean wanted = true;

    private GrugFrameRequest() {}

    /**
     * A test's room is built and it has asked for no frame yet, so frames from here on are skipped
     * until it asks.
     *
     * <p>Called after the room build, whose own frames are drawn: the room dirties the renderer,
     * and a capture that follows would otherwise read a frame from before it was drawn.
     */
    public static void beginTest() {
        wanted = false;
    }

    /** The test ended, so the client draws again until the next test's room is built. */
    public static void endTest() {
        wanted = true;
    }

    /**
     * The test asked for a frame, which stays on for the rest of it.
     *
     * <p>Staying on rather than following each call is what makes the settle wait fold in: a test
     * that has captured once captures again, and the frames between two captures are the ones its
     * next wait counts. A test that never asks leaves the flag off, and draws nothing from its room
     * build to its last tick.
     */
    public static void request() {
        wanted = true;
    }

    /** Whether a frame drawn now is one the running test asked for. */
    public static boolean wanted() {
        return wanted;
    }
}
