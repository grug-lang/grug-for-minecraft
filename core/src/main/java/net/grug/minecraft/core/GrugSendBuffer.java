package net.grug.minecraft.core;

/**
 * Whether a dedicated server may run another tick of its world, given how much of the joined
 * client's connection it still holds unwritten (#1802).
 *
 * <p>Alpha's connection, and Beta's, keep what the server has queued but not yet written, and close
 * the player once that passes {@link #GAME_DROP_BYTES}, {@code Send buffer overflow}. The write
 * thread drains one packet per loop and blocks inside the socket when the other side is not
 * reading, so a client that is behind turns the server's production into that backlog. The harness
 * produces far more than the game's own twenty ticks a second: a test asks for a batch of world
 * ticks inside one of its own, and the server runs its game-speed ticks before every runner tick,
 * so a merge pass queues packets faster than a client applying them can read.
 *
 * <p>So the server pauses: while the connection holds more than {@link #LOW_WATER_BYTES} the world
 * is not advanced, and the game's own loop lets the write thread drain. The pause is bounded by
 * {@link #MAX_PAUSE_TICKS}, so a client that stopped reading altogether still reaches the game's
 * own check and fails the run with the reason the game gives, rather than hanging it.
 *
 * <p>The arm is in the loader, which is the only place that can read its connection's backlog, and
 * it asks this class rather than comparing numbers itself, because a comparison is a rule and every
 * rule here has a test.
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
     * leaves a whole burst of world ticks room to grow the queue between two checks without
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

    /** How many world ticks this pause has taken so far, reset once the backlog is drained. */
    private int pausedTicks;

    /**
     * Whether the world waits this tick.
     *
     * <p>A backlog that has drained ends the pause, so the next one starts with its full budget.
     * One that stays up longer than {@link #MAX_PAUSE_TICKS} is let through from then until it
     * drains: the connection is still the game's to close, and the run has to end one way or the
     * other.
     *
     * @param queuedBytes what the joined client's connection holds unwritten
     */
    public boolean pause(int queuedBytes) {
        if (queuedBytes <= LOW_WATER_BYTES) {
            pausedTicks = 0;
            return false;
        }
        if (pausedTicks >= MAX_PAUSE_TICKS) {
            return false;
        }
        pausedTicks++;
        return true;
    }
}
