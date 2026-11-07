package net.grug.minecraft.forge;

import net.grug.minecraft.grug.GrugGenerated;

/**
 * Counts the container clicks the integrated server has applied, so the client of a single-player
 * run can hold the runner tick that reads a click until the click packet it sent has landed
 * (#1094).
 *
 * <p>A dedicated server gets that ordering from the control channel: the client sends the click
 * packet before it answers the forwarded call, so the packet is on the server's task queue by the
 * time the call returns, and {@code applyClientWork} reads it before the next tick. In single
 * player the press is made by the same process's client thread and the click packet only reaches
 * the integrated server's queue later, through the network threads, so a runner tick queued for the
 * same client tick would assert before the packet was read. The hold is taken on the client thread,
 * before the tick is queued, because the task itself runs on the integrated server's thread where
 * {@code MinecraftServer.execute} runs a task inline rather than queueing it; see {@code
 * GrugModLoader#advanceTestRunner}.
 *
 * <p>The count is read on the client thread, while the click handler runs on the server thread, so
 * the counts are guarded by the class's monitor.
 */
public final class GrugClientClicks {
    /**
     * How long the runner holds a tick for a click packet before it runs it anyway. The handler is
     * a task on the server thread and normally runs at once; this only covers a packet that was
     * sent but never handled, so the test's own assertion reports the truth rather than the run
     * waiting forever.
     */
    static final long TIMEOUT_MILLIS = 5_000;

    private static long expected;
    private static long applied;
    private static long deadline;

    private GrugClientClicks() {}

    /**
     * Says that this client is about to send a container click packet, and that the runner must
     * wait for the click handler it reaches. Called on the client thread from the game's own click
     * send, before the client's next tick can queue a runner tick for it.
     */
    public static synchronized void expect() {
        expected++;
        deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    }

    /**
     * Counts one click the server has handled. Called on the server thread. A handler that arrives
     * after its expectation was dropped, by a test's start, a closed screen, a timed-out packet or
     * the end of a run, is ignored rather than counted against a later click (#1094): the count
     * never passes the number of clicks still expected, which is why this clamps instead of adding.
     */
    public static synchronized void applied() {
        applied = Math.min(applied + 1, expected);
    }

    /**
     * Forgets every expectation, so a click whose packet never reached the handler cannot make a
     * later click wait for this one's timeout, and a handler that arrives late cannot answer a
     * later click's expectation. A test's start, a screen's close and the end of a run are the
     * moments an expected click has been abandoned rather than merely slow (#1094).
     */
    public static synchronized void reset() {
        expected = 0;
        applied = 0;
        deadline = 0;
    }

    /**
     * Whether a click packet this client sent has not been applied by the server yet.
     *
     * <p>Excluded from coverage: the timeout arm only runs when a click packet was sent and never
     * handled, which no test can make happen without breaking the game's own packet delivery.
     */
    @GrugGenerated(
            "the timeout arm needs a click packet the integrated server never handles, which no"
                    + " test can force")
    static synchronized boolean pending() {
        if (applied >= expected) {
            return false;
        }
        if (System.currentTimeMillis() >= deadline) {
            // The packet was never handled: the expectation is dropped rather than kept, so this
            // click's loss cannot hold the next one for a timeout of its own. The test that sent
            // the click reports what did not happen with its own assertion.
            expected = applied;
            return false;
        }
        return true;
    }
}
