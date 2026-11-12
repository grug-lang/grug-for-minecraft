package net.grug.minecraft.core;

import java.util.List;

/**
 * What a dedicated-server run decides about its players (#1027): whether the player who started the
 * run is still connected.
 *
 * <p>A run belongs to one player, and that player is the test player: the room, the held item and
 * the cursor are built for them, and the forwarded calls go to their client. Asking whether the
 * server has <em>any</em> player is a different question, and with a second player connected it is
 * the wrong one: the run would carry on around a player who never started it. The loaders that ask
 * with {@code players.isEmpty()} or fall back to {@code players.get(0)} answer that wrong question,
 * which is the defect 1.20.6 had and this class keeps from coming back.
 *
 * <p>The starter is an opaque value so each loader can pass what it keeps: a player entity, or the
 * UUID 1.20.6's control channel names its starter by. {@code connected} has to hold the same kind
 * of value, one per player the server still has.
 */
public final class GrugRunPlayers {
    private GrugRunPlayers() {}

    /**
     * Whether the run has lost its starter, given the values that name the players still connected.
     *
     * <p>A second player still connected says nothing about the starter: the list is asked about
     * the starter itself, so a run whose starter left fails instead of moving to somebody else.
     */
    public static boolean starterLeft(List<?> connected, Object starter) {
        return starter == null || !connected.contains(starter);
    }
}
