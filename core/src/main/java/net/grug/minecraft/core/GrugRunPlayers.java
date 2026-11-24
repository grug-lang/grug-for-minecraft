package net.grug.minecraft.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a dedicated-server run decides about its players: whether the player who started the run is
 * still connected (#1027), and which players the run keeps out of the world's damage (#1738).
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

    /**
     * Whether the second player belongs in the starter's view now: the current test is the one that
     * captures that player's model, it asked to run another tick, and the runner has not finished.
     * {@code path} is null between tests.
     */
    public static boolean modelTestWanted(
            String path, String modelTest, boolean testNotDone, boolean runnerFinished) {
        return path != null && path.endsWith(modelTest) && testNotDone && !runnerFinished;
    }

    /**
     * The players a dedicated run still has to keep out of the world's damage (#1738): those it
     * owns and that the world can still hurt. Empty on a server whose players the run did not
     * invite, because those players keep the world's own rules.
     *
     * <p>A run owns its players. It moves them where the tests need them, stands them in captures
     * and reads where they stand, and no test is about the world hurting them. The leg that
     * launched a second client joins its players in the world's default game mode, survival, in a
     * save that already holds hostile mobs, and a zombie slew the second player about a minute into
     * a run. A dead player's body lies down in the game's {@code DYING} pose, out of the crop the
     * player model test captures, so that crop stayed the empty room and the test waited out its
     * bound (#1738). A server the run did not invite keeps the world's own rules, so only the leg
     * that is given a second player marks one.
     *
     * <p>Every player is kept, not only the second one: the run moves the starter too, and a
     * starter slain in the middle of a test would end the run the same way. A player that is
     * already invulnerable is left out, so the caller marks each player once however often it asks.
     */
    public static <T> List<T> playersStillToKeep(
            List<T> players, Predicate<T> invulnerable, boolean legOwnsItsPlayers) {
        List<T> due = new ArrayList<>();
        if (!legOwnsItsPlayers) {
            return due;
        }
        for (T player : players) {
            if (!invulnerable.test(player)) {
                due.add(player);
            }
        }
        return due;
    }
}
