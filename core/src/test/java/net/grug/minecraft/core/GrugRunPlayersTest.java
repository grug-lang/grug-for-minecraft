package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The one question a dedicated-server run asks about its players (#1027): has the starter left?
 *
 * <p>The defect this pins: 1.20.6 asked whether the server had any player, so a second player who
 * stayed connected hid the starter leaving, and the run carried on around the player who never
 * started it. Every case here is about the starter and nobody else.
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
}
