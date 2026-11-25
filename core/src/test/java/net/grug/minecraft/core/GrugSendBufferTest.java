package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When a dedicated server stops advancing its world for a client that is behind (#1802). */
class GrugSendBufferTest {
    @Test
    void anEmptyBacklogNeverPauses() {
        GrugSendBuffer buffer = new GrugSendBuffer();

        assertFalse(buffer.pause(0));
    }

    @Test
    void theMarkItselfDoesNotPause() {
        GrugSendBuffer buffer = new GrugSendBuffer();

        assertFalse(buffer.pause(GrugSendBuffer.LOW_WATER_BYTES));
    }

    @Test
    void oneByteOverTheMarkPauses() {
        GrugSendBuffer buffer = new GrugSendBuffer();

        assertTrue(buffer.pause(GrugSendBuffer.LOW_WATER_BYTES + 1));
    }

    @Test
    void thePauseIsBoundedAndLetsTheWorldThroughAfterwards() {
        GrugSendBuffer buffer = new GrugSendBuffer();
        int backlog = GrugSendBuffer.LOW_WATER_BYTES + 1;

        for (int tick = 0; tick < GrugSendBuffer.MAX_PAUSE_TICKS; tick++) {
            assertTrue(buffer.pause(backlog), "tick " + tick + " of the pause");
        }
        assertFalse(buffer.pause(backlog));
        assertFalse(buffer.pause(backlog));
    }

    @Test
    void aDrainedBacklogGivesTheNextPauseItsFullBudget() {
        GrugSendBuffer buffer = new GrugSendBuffer();
        int backlog = GrugSendBuffer.LOW_WATER_BYTES + 1;
        for (int tick = 0; tick < GrugSendBuffer.MAX_PAUSE_TICKS; tick++) {
            buffer.pause(backlog);
        }
        assertFalse(buffer.pause(backlog));

        assertFalse(buffer.pause(0));

        for (int tick = 0; tick < GrugSendBuffer.MAX_PAUSE_TICKS; tick++) {
            assertTrue(buffer.pause(backlog), "tick " + tick + " of the second pause");
        }
        assertFalse(buffer.pause(backlog));
    }

    @Test
    void theMarkLeavesRoomForABurstUnderTheGamesDrop() {
        assertEquals(1048576, GrugSendBuffer.GAME_DROP_BYTES);
        assertTrue(GrugSendBuffer.LOW_WATER_BYTES < GrugSendBuffer.GAME_DROP_BYTES / 2);
    }
}
