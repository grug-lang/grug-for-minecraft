package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Covers the region a dedicated server sends its joined client again in {@link GrugLightResend}.
 */
class GrugLightResendTest {

    @Test
    void nothingIsOwedUntilABlockChanges() {
        GrugLightResend resend = new GrugLightResend();

        assertFalse(resend.pending());
        assertTrue(resend.due().isEmpty());
        assertTrue(resend.due().isEmpty());
    }

    @Test
    void theRegionFollowsTheTickThatMadeTheChange() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(5, 64, 5);

        assertTrue(resend.pending());
        assertTrue(resend.due().isEmpty(), "the poll of the changing tick sends nothing");
        assertTrue(resend.pending());

        List<int[]> regions = resend.due();

        assertEquals(1, regions.size());
        assertArrayEquals(new int[] {4, 63, 4, 3, 3, 3}, regions.get(0));
        assertFalse(resend.pending());
        assertTrue(resend.due().isEmpty(), "what was sent is not owed again");
    }

    @Test
    void aChangeAfterTheWaitStartsTheWaitOver() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(5, 64, 5);
        assertTrue(resend.due().isEmpty());
        resend.noteBlock(6, 64, 5);

        assertTrue(resend.due().isEmpty(), "the second change's tick has not ended");
        assertArrayEquals(new int[] {4, 63, 4, 4, 3, 3}, resend.due().get(0));
    }

    @Test
    void theBoxGrowsInEveryDirection() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(5, 64, 5);
        resend.noteBlock(2, 70, 9);
        resend.noteBlock(8, 60, 1);
        resend.due();

        assertArrayEquals(new int[] {1, 59, 0, 9, 13, 11}, resend.due().get(0));
    }

    @Test
    void aBoxOverSeveralChunksIsSplitPerChunk() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(15, 10, 15);
        resend.noteBlock(15, 10, 16);
        resend.due();

        List<int[]> regions = resend.due();

        assertEquals(4, regions.size());
        assertArrayEquals(new int[] {14, 9, 14, 2, 3, 2}, regions.get(0));
        assertArrayEquals(new int[] {14, 9, 16, 2, 3, 2}, regions.get(1));
        assertArrayEquals(new int[] {16, 9, 14, 1, 3, 2}, regions.get(2));
        assertArrayEquals(new int[] {16, 9, 16, 1, 3, 2}, regions.get(3));
    }

    @Test
    void theRegionStaysInsideTheWorldsHeight() {
        GrugLightResend low = new GrugLightResend();
        low.noteBlock(0, 0, 0);
        low.due();
        assertArrayEquals(new int[] {-1, 0, -1, 1, 2, 1}, low.due().get(0));

        GrugLightResend high = new GrugLightResend();
        high.noteBlock(0, 127, 0);
        high.due();
        assertArrayEquals(new int[] {-1, 126, -1, 1, 2, 1}, high.due().get(0));
    }

    @Test
    void aBlockOutsideTheWorldsHeightOwesNothingToSend() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(0, 200, 0);
        resend.due();

        assertTrue(resend.due().isEmpty());
        assertFalse(resend.pending());
    }
}
