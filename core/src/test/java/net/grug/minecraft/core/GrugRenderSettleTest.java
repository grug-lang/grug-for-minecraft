package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the frame rule in {@link GrugRenderSettle}. */
class GrugRenderSettleTest {

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
}
