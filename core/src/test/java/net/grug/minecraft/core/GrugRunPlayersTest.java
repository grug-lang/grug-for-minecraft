package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The questions a dedicated-server run asks about its players: has the starter left (#1027), and
 * which players must it keep out of the world's damage (#1738)?
 *
 * <p>The first defect this pins: 1.20.6 asked whether the server had any player, so a second player
 * who stayed connected hid the starter leaving, and the run carried on around the player who never
 * started it. Every case about that is about the starter and nobody else.
 *
 * <p>The second: the leg with a second client joins its players in survival, and a zombie slew the
 * second player about a minute into a run. A slain player's body lies down in the game's {@code
 * DYING} pose, below the crop the player model test captures, so nothing entered that crop and the
 * test waited out its bound (#1738). The run keeps every player it owns out of the world's damage.
 */
class GrugRunPlayersTest {
    private static final Object STARTER = "alice";
    private static final Object SECOND = "bob";

    @Test
    void aSecondPlayerStillConnectedDoesNotStandInForTheStarter() {
        // The case the old question got wrong: the list is not empty, but the starter is not in it.
        List<?> connected = Collections.singletonList(SECOND);

        assertTrue(GrugRunPlayers.starterLeft(connected, STARTER));
    }

    @Test
    void theStarterStillConnectedIsNotGone() {
        List<?> connected = Arrays.asList(SECOND, STARTER);

        assertFalse(GrugRunPlayers.starterLeft(connected, STARTER));
    }

    @Test
    void theStarterAloneIsNotGone() {
        assertFalse(GrugRunPlayers.starterLeft(Collections.singletonList(STARTER), STARTER));
    }

    @Test
    void anEmptyServerHasLostItsStarter() {
        assertTrue(GrugRunPlayers.starterLeft(Collections.emptyList(), STARTER));
    }

    @Test
    void aRunThatNeverHadAStarterHasLostIt() {
        // Reached when the start is cleared between two tests: the run must not continue either.
        assertTrue(GrugRunPlayers.starterLeft(Collections.singletonList(SECOND), null));
    }

    @Test
    void theSameRuleHoldsForTheUuidsOnePointTwentySixKeeps() {
        // 1.20.6's control channel names its starter by UUID, so its connected list is UUIDs.
        UUID starter = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertTrue(GrugRunPlayers.starterLeft(Collections.singletonList(other), starter));
        assertFalse(GrugRunPlayers.starterLeft(Arrays.asList(other, starter), starter));
    }

    @Test
    void theModelTestWantsAPlayerInViewOnlyWhileItRunsAndTheRunnerIsNotFinished() {
        assertTrue(GrugRunPlayers.modelTestWanted("a/m-Test.grug", "m-Test.grug", true, false));
        assertFalse(GrugRunPlayers.modelTestWanted(null, "m-Test.grug", true, false));
        assertFalse(GrugRunPlayers.modelTestWanted("a/o-Test.grug", "m-Test.grug", true, false));
        assertFalse(GrugRunPlayers.modelTestWanted("a/m-Test.grug", "m-Test.grug", false, false));
        assertFalse(GrugRunPlayers.modelTestWanted("a/m-Test.grug", "m-Test.grug", true, true));
    }

    @Test
    void theLegThatLaunchedASecondClientKeepsEveryPlayerOnceAndThenNeedsNothing() {
        // #1738: the world slew the second player out of the capture's crop. The leg owns both
        // players, so a run that ticks twenty times a second must mark each of them and then stop
        // asking.
        List<Object> players = Arrays.asList(STARTER, SECOND);
        Set<Object> invulnerable = new HashSet<>();

        for (int tick = 0; tick < 100; tick++) {
            List<Object> due =
                    GrugRunPlayers.playersStillToKeep(players, invulnerable::contains, true);
            if (tick == 0) {
                // The starter is kept too: the run moves it, and its death ends the run as well.
                assertEquals(players, due);
            } else {
                assertTrue(due.isEmpty());
            }
            invulnerable.addAll(due);
        }

        assertEquals(new HashSet<>(players), invulnerable);
    }

    @Test
    void theStarterAloneIsStillAKeptPlayer() {
        List<Object> players = Collections.singletonList(STARTER);

        assertEquals(players, GrugRunPlayers.playersStillToKeep(players, player -> false, true));
    }

    @Test
    void aServerTheRunDidNotInviteKeepsItsOwnPlayersRules() {
        // The marker names the second client of the leg that launched one; a server without it has
        // players of its own, whose health is that server's business.
        List<Object> players = Arrays.asList(STARTER, SECOND);

        assertTrue(GrugRunPlayers.playersStillToKeep(players, player -> false, false).isEmpty());
    }

    @Test
    void aLegThatOwnsNoPlayersStillKeepsNone() {
        assertTrue(
                GrugRunPlayers.playersStillToKeep(Collections.emptyList(), player -> false, true)
                        .isEmpty());
    }
}
