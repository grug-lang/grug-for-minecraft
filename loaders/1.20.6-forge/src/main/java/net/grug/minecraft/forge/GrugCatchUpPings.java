package net.grug.minecraft.forge;

/**
 * The ping the test boundary sends the client, and the pong that proves the client processed
 * everything sent before it.
 *
 * <p>The room build clears the test area on the integrated server, and the client learns of that
 * clearing from packets it reads a tick or more later. A ping sent after the clearing was broadcast
 * is queued behind it on the same connection, and the client answers a ping only after it has
 * processed the packets in front of it, so a pong proves the client holds the cleared blocks and
 * can no longer draw the block entities of the test before. See {@code GrugTestBoundary} and #836.
 *
 * <p>The ping's id makes an old answer harmless: the counter keeps going for the whole run, so no
 * two pings share an id, and a caller counts only the answer to the id it waits for. Recording only
 * a higher id than the one already answered also keeps a late answer to an earlier ping from
 * replacing a newer one. The server's {@code handlePong} is empty in vanilla, which is why {@link
 * net.grug.minecraft.forge.mixin.ServerPongMixin} has to record the answer itself.
 */
public final class GrugCatchUpPings {
    /** The id the next ping gets. Only the server thread opens a ping. */
    private static int nextId = 1;

    /** The id of the newest ping the client has answered, or 0 before the first answer. */
    private static volatile int answeredId = 0;

    private GrugCatchUpPings() {}

    /**
     * Starts a ping and answers its id, which the caller sends to the client. The counter keeps
     * going for the whole run, so no two pings share an id.
     */
    public static synchronized int open() {
        return nextId++;
    }

    /**
     * Records the client's answer to the ping with this id, keeping the newest answer when one for
     * an earlier ping arrives late. Called from the server's packet handler.
     */
    public static void notePong(int id) {
        answeredId = Math.max(answeredId, id);
    }

    /** Whether the client has answered the ping with this id. */
    public static boolean isAnswered(int id) {
        return id != 0 && answeredId == id;
    }
}
