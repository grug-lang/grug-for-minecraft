package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugClientRelightTest {

    @Test
    void sweepsOnceWhenTheFrameIsReady() {
        assertTrue(GrugClientRelight.sweepNow(true, false));
    }

    @Test
    void doesNotSweepBeforeTheFrameIsReady() {
        assertFalse(GrugClientRelight.sweepNow(false, false));
    }

    @Test
    void doesNotSweepTwiceForOneCapture() {
        assertFalse(GrugClientRelight.sweepNow(true, true));
        assertFalse(GrugClientRelight.sweepNow(false, true));
    }

    @Test
    void theBoxSurroundsTheEyeInBlocks() {
        assertArrayEquals(
                new int[] {-10, 60, -10, 10, 90, 10}, GrugClientRelight.box(0.5, 70.62, 0.5));
        // Negative coordinates floor toward minus infinity, as the world's blocks do.
        assertArrayEquals(
                new int[] {-36, 60, -76, -16, 90, -56}, GrugClientRelight.box(-25.5, 70.62, -65.5));
    }
}
