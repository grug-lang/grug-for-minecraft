package net.grug.minecraft.core;

/**
 * Which end of a connection a grug packet travels between, as the game's own packet tables state
 * it.
 *
 * <p>Every loader this repository supports registers a packet with a method that takes one flag per
 * direction, and they all put the server-to-client flag first, but under names that do not say so:
 * 1.2.5's private {@code addIdClassMapping} calls it {@code par1}, Ornithe's {@code register} names
 * it {@code s2c}, and StationAPI's event calls it client-receivable. Spelling the direction once,
 * here, is what keeps a loader from registering a packet the receiver it was sent from refuses,
 * which closes the connection.
 *
 * <p>The two constants name the directions a caller needs. {@link #BOTH_WAYS} is the control
 * channel, which both ends send on. {@link #SERVER_TO_CLIENT} is a block entity's saved data, which
 * only the side that simulates the block entity sends, and only a client applies.
 */
public enum GrugPacketDirection {
    /** Both ends register and accept it, as the control channel does. */
    BOTH_WAYS(true, true),
    /** Only the server sends it and only a client accepts it, as saved data does. */
    SERVER_TO_CLIENT(false, true);

    private final boolean clientToServer;
    private final boolean serverToClient;

    GrugPacketDirection(boolean clientToServer, boolean serverToClient) {
        this.clientToServer = clientToServer;
        this.serverToClient = serverToClient;
    }

    /** Whether a client may send it to a server. */
    public boolean clientToServer() {
        return clientToServer;
    }

    /** Whether a server may send it to a client. */
    public boolean serverToClient() {
        return serverToClient;
    }
}
