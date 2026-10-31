package net.grug.minecraft.core;

/**
 * Whether the running test has asked for a drawn frame yet, for the #760 measurement.
 *
 * <p>Experiment only: this class is on the throwaway branch that skips drawing for a test that has
 * not asked for a frame, and is deleted with it. A real change would put the same flag behind the
 * runner, which is the only place that knows where one test ends and the next begins.
 *
 * <p>Drawing is on outside a test, so the client draws its title screen, the world load and the
 * frames between tests as it always did. The runner turns it off once a test's room is built and
 * back on when the test ends, and the calls that need a drawn frame turn it on: {@code
 * Test.screenshot}, a {@code world_settled} wait, and either capture call.
 */
public final class GrugDrawDemand {
    /** On by default, so nothing outside a test changes what the client draws. */
    private static volatile boolean requested = true;

    private GrugDrawDemand() {}

    /** The test whose room was just built has asked for no frame yet. */
    public static void beginTest() {
        requested = false;
    }

    /** The test ended, so the client draws as it always did until the next one asks. */
    public static void endTest() {
        requested = true;
    }

    /** The test asked for a frame, which stays on for the rest of it. */
    public static void request() {
        requested = true;
    }

    /** Whether a frame drawn now is one the test asked for. */
    public static boolean shouldDraw() {
        return requested;
    }
}
