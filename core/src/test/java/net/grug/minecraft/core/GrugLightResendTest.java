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
        assertArrayEquals(new int[] {4, 62, 4, 3, 4, 3}, regions.get(0));
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
        assertArrayEquals(new int[] {4, 62, 4, 4, 4, 3}, resend.due().get(0));
    }

    @Test
    void theBoxGrowsInEveryDirection() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(5, 64, 5);
        resend.noteBlock(2, 70, 9);
        resend.noteBlock(8, 60, 1);
        resend.due();

        assertArrayEquals(new int[] {1, 58, 0, 9, 14, 11}, resend.due().get(0));
    }

    @Test
    void aBoxOverSeveralChunksIsSplitPerChunk() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(15, 10, 15);
        resend.noteBlock(15, 10, 16);
        resend.due();

        List<int[]> regions = resend.due();

        assertEquals(4, regions.size());
        assertArrayEquals(new int[] {14, 8, 14, 2, 4, 2}, regions.get(0));
        assertArrayEquals(new int[] {14, 8, 16, 2, 4, 2}, regions.get(1));
        assertArrayEquals(new int[] {16, 8, 14, 1, 4, 2}, regions.get(2));
        assertArrayEquals(new int[] {16, 8, 16, 1, 4, 2}, regions.get(3));
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

    @Test
    void theSettleAnswerIsNoUntilTheRegionWentOut() {
        GrugLightResend resend = new GrugLightResend();

        assertFalse(resend.pending(), "nothing is owed, so the world is settled");

        resend.noteBlock(5, 64, 5);
        assertTrue(resend.pending(), "the client does not hold this server's light yet");

        assertTrue(resend.due().isEmpty(), "the poll that only arms the send owes the region");
        assertTrue(resend.pending(), "arming the send is not sending it, so not settled either");

        resend.due();
        assertFalse(resend.pending(), "the region went out, so the next poll may settle");
    }

    @Test
    void theSentHeightIsWholePairsOfRowsBecauseAPacketsLightCopyIs() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(5, 64, 5);
        resend.due();

        List<int[]> regions = resend.due();

        // The changed block's box is 63..65, which the pair alignment widens to 62..65: a region
        // packet copies each column's light one byte per pair of rows, so an odd height or an odd
        // lower edge would leave its top row as the client had it, and the light ask would compare
        // a cell the server never sent.
        assertArrayEquals(new int[] {4, 62, 4, 3, 4, 3}, regions.get(0));
        assertEquals(0, resend.lastBox()[1] % 2);
        assertEquals(1, resend.lastBox()[4] % 2);
        assertArrayEquals(new int[] {4, 62, 4, 6, 65, 6}, resend.lastBox());
    }

    @Test
    void aRearmedRegionIsSentAgainWithoutGrowingTheBox() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(5, 64, 5);
        resend.due();
        resend.due();
        assertFalse(resend.pending());

        resend.rearm();

        assertTrue(resend.pending(), "the client's light differed, so the region is owed again");
        assertTrue(resend.due().isEmpty(), "the poll that only arms the send");
        List<int[]> again = resend.due();
        assertEquals(1, again.size());
        assertArrayEquals(new int[] {4, 62, 4, 3, 4, 3}, again.get(0));
        assertArrayEquals(new int[] {4, 62, 4, 6, 65, 6}, resend.lastBox());
        assertFalse(resend.pending(), "what was sent again is not owed a third time");
    }

    @Test
    void rearmingBeforeAnyRegionWentOutSendsNothing() {
        GrugLightResend resend = new GrugLightResend();

        resend.rearm();

        assertFalse(resend.pending());
        assertTrue(resend.due().isEmpty());
        assertEquals(0, resend.lastBox().length);
    }

    @Test
    void theBoxOfTheLastSendIsKept() {
        GrugLightResend resend = new GrugLightResend();
        assertEquals(0, resend.lastBox().length);
        resend.noteBlock(5, 64, 5);
        resend.noteBlock(2, 70, 9);
        resend.due();
        assertEquals(0, resend.lastBox().length, "nothing was sent for the waiting poll");
        resend.due();

        assertArrayEquals(new int[] {1, 62, 4, 6, 71, 10}, resend.lastBox());
    }
}
