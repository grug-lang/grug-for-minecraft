package net.grug.minecraft.core;

/**
 * Decides when the test runner forces one world save for a test.
 *
 * <p>The save indicator is raised by the game's own autosave, which fires on the game's timer, so a
 * test that captures every few ticks cannot know when the text is on the screen: it would have to
 * run a window as long as the timer's worst gap, which is longer than a loader job may take. A test
 * whose file name starts with {@link #PREFIX} instead gets one save at {@link #TICK}, made the way
 * the autosave makes it, and can capture around that tick. See #1132.
 *
 * <p>The prefix is deliberately narrow, so a test about the autosave itself does not take it by
 * accident, and the runner says a line when it forces the save, so a run's log shows the extra save
 * rather than leaving it silent.
 *
 * <p>The decision is a pure function of the path and the tick, like {@link GrugTestOrder}, so it is
 * tested without a runner or a client.
 */
public final class GrugForcedAutosave {
    /** The prefix of a test file name that asks for the forced save. */
    public static final String PREFIX = "forced_autosave_";

    /** The tick of a marked test before which the runner forces the save. */
    public static final long TICK = 100;

    private GrugForcedAutosave() {}

    /**
     * Whether the runner forces the save before the test's tick {@code tick} runs.
     *
     * @param testPath the path of the test, as the runner lists it
     * @param tick the tick of the test that is about to run
     */
    public static boolean isDue(String testPath, long tick) {
        if (tick != TICK) {
            return false;
        }
        String name = testPath.substring(testPath.lastIndexOf('/') + 1);
        return name.startsWith(PREFIX);
    }
}
