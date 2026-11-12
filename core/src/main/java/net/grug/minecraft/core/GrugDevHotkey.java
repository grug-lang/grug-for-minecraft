package net.grug.minecraft.core;

/**
 * What one press of the dev hotkey (R) may do, as a decision a unit test can call.
 *
 * <p>The hotkey starts a client-only run, which the client advances against its own world: 1.20.6
 * queues each tick onto the integrated server's thread. A client joined to a server has no
 * integrated server, so a run started there never reaches the end that calls {@code endRun()}, and
 * the test-sized window, the switched-off dither, the minimal particles, the stopped tutorial, the
 * shrunk view and the parked cursor stay for the client's life (#1271). The press therefore has to
 * be refused before the window is taken, rather than taken and left behind.
 *
 * <p>A joined client's run is the control run the server asks that client to start, which asks the
 * window before it claims anything (#1040), so the hotkey invents no joined behaviour of its own.
 */
public final class GrugDevHotkey {

    private GrugDevHotkey() {}

    /**
     * Whether a press of R may start a client-only run, given whether this client has an integrated
     * server. Both answers are pinned by {@code GrugDevHotkeyTest}, because the key press itself is
     * not reachable from a grug test or a unit test.
     */
    public static boolean startsLocalRun(boolean integratedServerPresent) {
        return integratedServerPresent;
    }
}
