package net.grug.minecraft.core;

import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * The pace a dedicated server runs its world at, given how much of the joined client's connection
 * it still holds unwritten (#1802).
 *
 * <p>Alpha's connection, and Beta's, keep what the server has queued but not yet written, and close
 * the player once that passes {@link #GAME_DROP_BYTES}, {@code Send buffer overflow}. The write
 * thread drains one packet per loop and blocks inside the socket when the other side is not
 * reading, so a client that is behind turns the server's production into that backlog. The harness
 * produces far more than the game's own twenty ticks a second: a test asks for a batch of world
 * ticks inside one of its own, and the server runs its game-speed ticks before every runner tick,
 * so a merge pass queues packets faster than a client applying them can read.
 *
 * <p>So the server hands its world ticks here: while the connection holds more than {@link
 * #LOW_WATER_BYTES} the tick is not run and the game's own loop comes around again, which lets the
 * write thread drain. The pause is bounded by {@link #MAX_PAUSE_TICKS}, so a client that stopped
 * reading altogether still reaches the game's own check and fails the run with the reason the game
 * gives, rather than hanging it.
 *
 * <p>Only a CI run paces, because that is the only place a harness outruns a client: an ordinary
 * server drops a client that far behind, as the game always has, instead of freezing its world for
 * the players who are keeping up.
 *
 * <p>The rule lives here, and a loader only reads its connection's backlog and hands in the step,
 * because a comparison is a rule and every rule here has a test.
 */
public final class GrugSendBuffer {
    /**
     * The backlog at which the game closes the player, in bytes. It is evidence rather than a
     * constant this class may choose: Alpha's and Beta's {@code Connection} compare their own queue
     * counter against it once per tick.
     */
    public static final int GAME_DROP_BYTES = 1048576;

    /**
     * The backlog at which the server stops advancing the world: a quarter of the game's, which
     * leaves a whole server tick of world ticks room to grow the queue between two checks without
     * reaching the drop.
     */
    public static final int LOW_WATER_BYTES = GAME_DROP_BYTES / 4;

    /**
     * How many world ticks one pause may take before the world advances regardless. The a1 leg's
     * merge pass leaves the client about twenty seconds behind, so thirty seconds covers a slow
     * client with room, and a client that stopped reading fails the run in the minute rather than
     * after the run's own timeout.
     */
    public static final int MAX_PAUSE_TICKS = 600;

    /** Whether this buffer paces at all, which it does only when the environment says it is CI. */
    private final boolean armed;

    /** How many world ticks this pause has taken so far, reset once it ends. */
    private int pausedTicks;

    /**
     * Whether the end of the spent budget has been reported for the backlog the world is behind on,
     * so the line is printed once rather than on every tick that runs on.
     */
    private boolean ranOnReported;

    /** A buffer that paces when {@code env} says the run is CI's, and never otherwise. */
    public GrugSendBuffer(Function<String, String> env) {
        this.armed = "true".equals(env.apply("GRUG_CI"));
    }

    /**
     * Runs {@code tick} up to {@code ticks} times, stopping at the first one the joined client is
     * behind on, and answers how many ran.
     *
     * <p>A server tick passes 1, which is the tick it would otherwise run, and skips its runner and
     * its flush with it. A test's batch passes what {@code TestTickContext.tick_level} asked for
     * and answers what ran, which that call documents, so a test that asked for more waits for the
     * client instead of losing it.
     *
     * @param queuedBytes what the joined client's connection holds unwritten, or 0 when there is no
     *     joined client yet
     */
    public int advance(IntSupplier queuedBytes, int ticks, Runnable tick) {
        int ran = 0;
        while (ran < ticks) {
            if (wait(queuedBytes.getAsInt())) {
                break;
            }
            tick.run();
            ran++;
        }
        return ran;
    }

    /**
     * Whether this step waits, saying so once at each end of a wait.
     *
     * <p>The two lines are the only record that the harness chose to wait, which is what tells a
     * slow tick apart from a client that fell behind.
     */
    private boolean wait(int queuedBytes) {
        if (behind(queuedBytes)) {
            pausedTicks++;
            reportWaiting(queuedBytes);
            return true;
        }
        reportRanOn(queuedBytes);
        return false;
    }

    /**
     * Whether the world waits this step. A backlog that has ended ends the pause, so the next one
     * starts with its full budget; one that stays up longer than {@link #MAX_PAUSE_TICKS} is let
     * through until it ends, because the connection is still the game's to close and the run has to
     * end one way or the other.
     */
    private boolean behind(int queuedBytes) {
        return armed && queuedBytes > LOW_WATER_BYTES && pausedTicks < MAX_PAUSE_TICKS;
    }

    private void reportWaiting(int queuedBytes) {
        if (pausedTicks != 1) {
            return;
        }
        System.out.println(
                "[GRUG CI] The world waits: the joined client's connection holds "
                        + queuedBytes
                        + " bytes.");
    }

    /**
     * Says that a wait ended and the world ran on, once per wait, and starts a fresh budget only
     * once the backlog has dropped below {@link #LOW_WATER_BYTES}.
     *
     * <p>Reset on every tick that ran on instead, a client that stayed behind got its budget back
     * at once, so it advanced one tick per {@link #MAX_PAUSE_TICKS} calls and never caught up.
     * Waiting through the budget and then running until the backlog clears is what lets it.
     */
    private void reportRanOn(int queuedBytes) {
        if (pausedTicks != 0 && !ranOnReported) {
            System.out.println(
                    "[GRUG CI] The world ran on after "
                            + pausedTicks
                            + " ticks of waiting, with the joined client's connection holding "
                            + queuedBytes
                            + " bytes.");
            ranOnReported = true;
        }
        if (queuedBytes <= LOW_WATER_BYTES) {
            pausedTicks = 0;
            ranOnReported = false;
        }
    }
}
