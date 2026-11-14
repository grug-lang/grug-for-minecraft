package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The singleplayer owner's operator status, which no game keeps and a script can read back. */
class GrugOperatorStatusTest {
    @Test
    void theOwnerOfASingleplayerWorldIsAnOperatorUntilAScriptRemovesIt() {
        GrugOperatorStatus status = new GrugOperatorStatus();

        assertTrue(status.isOperator(true, false), "the owner counts with nothing revoked");

        status.revoked();
        assertFalse(status.isOperator(true, false), "a revoked owner is not an operator");

        status.granted();
        assertTrue(status.isOperator(true, false), "granting gives the owner back");
    }

    @Test
    void aPlayerWhoIsNotTheOwnerIsAnOperatorExactlyWhenTheListHoldsThem() {
        GrugOperatorStatus status = new GrugOperatorStatus();

        assertFalse(status.isOperator(false, false), "an unlisted player is not an operator");
        assertTrue(status.isOperator(false, true), "a listed player is");

        status.revoked();
        assertTrue(
                status.isOperator(false, true),
                "the owner's revoke is not about anyone else: the list still holds a listed"
                        + " player");
        assertFalse(status.isOperator(false, false), "and an unlisted one still is not");
    }

    @Test
    void aListedOwnerStaysAnOperatorWhateverWasRevoked() {
        GrugOperatorStatus status = new GrugOperatorStatus();
        status.revoked();

        assertTrue(status.isOperator(true, true), "the list is the game's own and wins");
    }
}
