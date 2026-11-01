package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Covers the rule that ends the boundary wait in {@link GrugTestBoundary}. */
class GrugTestBoundaryTest {

    @Test
    void everyCombinationOfBroadcastAndAcknowledgedIsAnswered() {
        for (int combination = 0; combination < 4; combination++) {
            boolean broadcast = (combination & 1) != 0;
            boolean acknowledged = (combination & 2) != 0;

            // The ping only means anything once the clearing is on the wire, so an answer to one
            // sent before the clearing does not count as caught up. This is the pair that makes
            // the boundary wait a proof rather than a guess.
            assertEquals(
                    !(broadcast && acknowledged),
                    GrugTestBoundary.clientCatchingUp(broadcast, acknowledged),
                    "broadcast " + broadcast + ", acknowledged " + acknowledged);
        }
    }
}
