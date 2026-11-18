package net.grug.minecraft.core;

/**
 * Decides whether a grug block entity tells the game it changed (#1393).
 *
 * <p>On a1.1.2_01 the game's {@code markDirty} on a block entity of a client joined to a server
 * sends that copy's whole NBT to the server in a block entity update packet, and the server reads
 * it into its own block entity. The client's copy holds no inventory, because the container's
 * contents travel in the menu's own packets, so the echo overwrote the server's slots with empty
 * ones: a click the server had just applied was erased by an update the client sent for an earlier
 * change, depending on which packet the server read first.
 */
public final class GrugClientCopyEcho {
    private GrugClientCopyEcho() {}

    /**
     * Whether a block entity may mark itself dirty. A client's copy of a server's block entity may
     * not, because the server's copy is the authority and what the client would send is stale; a
     * server's own copy and a singleplayer world's may, which is how the chunk gets saved.
     */
    public static boolean mayMarkDirty(boolean remoteWorld) {
        return !remoteWorld;
    }
}
