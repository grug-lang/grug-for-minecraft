package net.grug.minecraft.core;

/**
 * How long a client-only run lives, as decisions a unit test can call.
 *
 * <p>A local run advances against the client's own world: 1.20.6 queues each runner tick onto its
 * integrated server's thread. That server can go away mid-run, when the player quits to the title
 * screen for example, and then the run can never advance again. Left alone the runner stays set,
 * the end that restores the window is never reached, and the test-sized window, the switched-off
 * dither, the minimal particles, the stopped tutorial, the shrunk view and the parked cursor stay
 * for the client's life (#1276). Ending the run is what puts them back, through the same restore a
 * run that finishes uses.
 *
 * <p>{@link GrugDevHotkey} is the other half: a client with no integrated server is refused a run
 * in the first place (#1271), so the two decisions together cover a run that could never finish.
 */
public final class GrugLocalRun {

    private GrugLocalRun() {}

    /**
     * Whether a client-only run has to end because the integrated server it advances against is
     * gone.
     *
     * <p>Only a run that is running can end, so a client that never started one, or whose run
     * already finished, is untouched. A run can only start once its server exists, and a press is
     * refused without one, so a server that is merely loading never has a run in flight against it.
     * Both answers are pinned by {@code GrugLocalRunTest}, because the disconnect itself cannot be
     * produced from a grug test or a unit test.
     */
    public static boolean endsOnLostServer(
            boolean integratedServerPresent, boolean localRunActive) {
        return localRunActive && !integratedServerPresent;
    }
}
