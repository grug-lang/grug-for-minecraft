package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the frame rule in {@link GrugRenderSettle}. */
class GrugRenderSettleTest {

    @Test
    void aChangeMadeAfterTheLastFrameNeedsTwoFramesAfterIt() {
        GrugRenderSettle settle = new GrugRenderSettle();
        settle.onFrame(false);
        settle.onFrame(false);
        assertTrue(settle.settled());

        settle.noteBusy();

        assertFalse(settle.settled(), "The frames drawn before the change say nothing about it.");
        settle.onFrame(false);
        assertFalse(settle.settled());
        settle.onFrame(false);
        assertTrue(settle.settled());
    }

    @Test
    void pendingWorkAtTheMomentOfTheQuestionIsNotSettledAndCountsAsBusy() {
        GrugRenderSettle settle = new GrugRenderSettle();
        settle.onFrame(false);
        settle.onFrame(false);

        assertFalse(settle.settledNow(true));
        assertFalse(settle.settledNow(false), "No frame has been drawn since the pending work.");
        settle.onFrame(false);
        settle.onFrame(false);
        assertTrue(settle.settledNow(false));
    }

    @Test
    void noFrameDrawnYetIsNotSettled() {
        GrugRenderSettle settle = new GrugRenderSettle();

        assertFalse(settle.settled());
    }

    @Test
    void oneIdleFrameIsNotEnough() {
        GrugRenderSettle settle = new GrugRenderSettle();

        settle.onFrame(false);

        // The capture reads the frame before the last, so a single idle frame leaves nothing
        // settled for it to read.
        assertFalse(settle.settled());

        settle.onFrame(false);

        assertTrue(settle.settled());
    }

    @Test
    void aBusyFrameNeedsTwoIdleFramesAfterIt() {
        GrugRenderSettle settle = new GrugRenderSettle();

        settle.onFrame(true);

        assertFalse(settle.settled());

        settle.onFrame(false);

        assertFalse(settle.settled());

        settle.onFrame(false);

        assertTrue(settle.settled());
    }

    @Test
    void everyBusyFrameMovesTheFrameThatIsWaitedFor() {
        GrugRenderSettle settle = new GrugRenderSettle();
        settle.onFrame(false);
        settle.onFrame(false);
        settle.onFrame(true);

        assertFalse(settle.settled());

        settle.onFrame(false);

        assertFalse(settle.settled());

        settle.onFrame(false);

        assertTrue(settle.settled());
    }

    @Test
    void aResetForgetsEveryFrameBeforeIt() {
        GrugRenderSettle settle = new GrugRenderSettle();
        settle.onFrame(false);
        settle.onFrame(false);

        assertTrue(settle.settled());

        settle.reset();

        assertFalse(settle.settled());

        settle.onFrame(false);

        assertFalse(settle.settled());

        settle.onFrame(false);

        assertTrue(settle.settled());
    }

    @Test
    void lightIsSettledOnlyWhenNeitherTheEngineNorTheUpdateQueueHoldsWork() {
        assertTrue(GrugRenderSettle.lightSettled(false, true));
        // Work in the engine's own queues.
        assertFalse(GrugRenderSettle.lightSettled(true, true));
        // Light packets received and not applied yet, which the engine cannot see (#424).
        assertFalse(GrugRenderSettle.lightSettled(false, false));
        assertFalse(GrugRenderSettle.lightSettled(true, false));
    }
}
