package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the two directions the loaders register a packet with. The game keeps its own direction
 * sets private and only while a game runs, so this is what pins the meaning the three registration
 * sites pass on.
 */
class GrugPacketDirectionTest {

    @Test
    void theControlChannelTravelsBothWays() {
        assertTrue(GrugPacketDirection.BOTH_WAYS.clientToServer());
        assertTrue(GrugPacketDirection.BOTH_WAYS.serverToClient());
    }

    @Test
    void savedDataTravelsServerToClientOnly() {
        assertFalse(GrugPacketDirection.SERVER_TO_CLIENT.clientToServer());
        assertTrue(GrugPacketDirection.SERVER_TO_CLIENT.serverToClient());
    }
}
