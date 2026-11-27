package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/** Whether a dedicated server waits for a joined client that is behind (#1802). */
class GrugSendBufferTest {
    private static final int OVER_THE_MARK = GrugSendBuffer.LOW_WATER_BYTES + 1;

    /** Runs a step's worth of ticks and counts them, so a test can see what really ran. */
    private static final class Steps implements Runnable {
        private int ran = 0;

        @Override
        public void run() {
            ran++;
        }
    }

    private static GrugSendBuffer ciRun() {
        return new GrugSendBuffer(name -> "true");
    }

    @Test
    void aRunOutsideCiNeverWaits() {
        GrugSendBuffer buffer = new GrugSendBuffer(name -> null);
        Steps steps = new Steps();

        assertEquals(3, buffer.advance(() -> OVER_THE_MARK, 3, steps));
        assertEquals(3, steps.ran);
    }

    @Test
    void anEmptyBacklogRunsEveryStep() {
        GrugSendBuffer buffer = ciRun();
        Steps steps = new Steps();

        assertEquals(3, buffer.advance(() -> 0, 3, steps));
        assertEquals(3, steps.ran);
    }

    @Test
    void theMarkItselfDoesNotWait() {
        GrugSendBuffer buffer = ciRun();
        Steps steps = new Steps();

        assertEquals(1, buffer.advance(() -> GrugSendBuffer.LOW_WATER_BYTES, 1, steps));
        assertEquals(1, steps.ran);
    }

    @Test
    void aBacklogOverTheMarkStopsTheBatchAndTheNextOneRunsAgain() {
        GrugSendBuffer buffer = ciRun();
        AtomicInteger queued = new AtomicInteger(OVER_THE_MARK);
        Steps steps = new Steps();

        assertEquals(0, buffer.advance(queued::get, 3, steps));
        assertEquals(0, steps.ran);
        assertEquals(0, buffer.advance(queued::get, 3, steps));

        queued.set(0);
        assertEquals(3, buffer.advance(queued::get, 3, steps));
        assertEquals(3, steps.ran);
    }

    @Test
    void aBatchStopsAtTheStepTheClientFallsBehindOn() {
        GrugSendBuffer buffer = ciRun();
        AtomicInteger queued = new AtomicInteger(0);
        Steps steps = new Steps();

        assertEquals(
                1,
                buffer.advance(
                        queued::get,
                        5,
                        () -> {
                            steps.run();
                            queued.set(OVER_THE_MARK);
                        }));
        assertEquals(1, steps.ran);
    }

    @Test
    void theWaitIsBoundedAndLetsTheWorldThroughAfterwards() {
        GrugSendBuffer buffer = ciRun();
        Steps steps = new Steps();

        for (int tick = 0; tick < GrugSendBuffer.MAX_PAUSE_TICKS; tick++) {
            assertEquals(0, buffer.advance(() -> OVER_THE_MARK, 1, steps), "tick " + tick);
        }
        assertEquals(0, steps.ran);

        assertEquals(1, buffer.advance(() -> OVER_THE_MARK, 1, steps));
        assertEquals(1, steps.ran);
    }

    @Test
    void aWaitThatEndsWithTheBacklogUpKeepsTheBudgetSpentUntilItDrops() {
        GrugSendBuffer buffer = ciRun();
        Steps steps = new Steps();

        for (int tick = 0; tick < GrugSendBuffer.MAX_PAUSE_TICKS; tick++) {
            assertEquals(0, buffer.advance(() -> OVER_THE_MARK, 1, steps), "tick " + tick);
        }
        // The budget is spent and the client is still behind, so the world advances on every
        // call instead of starting another full pause. Starting one per call is the stall this
        // class exists to prevent: one tick per 601 calls never catches a client up.
        for (int advance = 0; advance < 5; advance++) {
            assertEquals(1, buffer.advance(() -> OVER_THE_MARK, 1, steps), "advance " + advance);
        }
        assertEquals(5, steps.ran);

        // Below the mark the next pause starts with its full budget again.
        assertEquals(1, buffer.advance(() -> GrugSendBuffer.LOW_WATER_BYTES, 1, steps));
        assertEquals(6, steps.ran);
        for (int tick = 0; tick < GrugSendBuffer.MAX_PAUSE_TICKS; tick++) {
            assertEquals(
                    0,
                    buffer.advance(() -> OVER_THE_MARK, 1, steps),
                    "tick " + tick + " of the next pause");
        }
        assertEquals(6, steps.ran, "The backlog that dropped below the mark ended the stall.");
    }

    @Test
    void aFinishedWaitGivesTheNextOneItsFullBudget() {
        GrugSendBuffer buffer = ciRun();
        AtomicInteger queued = new AtomicInteger(OVER_THE_MARK);
        Steps steps = new Steps();

        for (int tick = 0; tick < GrugSendBuffer.MAX_PAUSE_TICKS; tick++) {
            assertEquals(0, buffer.advance(queued::get, 1, steps));
        }
        assertEquals(1, buffer.advance(queued::get, 1, steps));
        assertEquals(1, steps.ran);

        queued.set(0);
        assertEquals(1, buffer.advance(queued::get, 1, steps));

        queued.set(OVER_THE_MARK);
        for (int tick = 0; tick < GrugSendBuffer.MAX_PAUSE_TICKS; tick++) {
            assertEquals(0, buffer.advance(queued::get, 1, steps), "tick " + tick);
        }
        assertEquals(2, steps.ran);
    }

    @Test
    void theMarkLeavesRoomForABurstUnderTheGamesDrop() {
        assertEquals(1048576, GrugSendBuffer.GAME_DROP_BYTES);
        assertTrue(GrugSendBuffer.LOW_WATER_BYTES < GrugSendBuffer.GAME_DROP_BYTES / 2);
    }
}
