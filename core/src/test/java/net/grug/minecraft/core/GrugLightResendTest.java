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
    void forgettingTheBoxSendsOnlyTheNextTestsOwnChanges() {
        // A test that never waits leaves its placements owed, and a test that did wait leaves the
        // box of its last send, which a divergence would arm again. Neither is the next test's: the
        // next test's box is its room and its own placements, so forgetting both keeps a wait from
        // comparing a cell no test touched (#1695).
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(100, 64, 100);
        resend.due();
        resend.due();
        resend.noteBlock(60, 64, 3);
        assertArrayEquals(new int[] {99, 62, 99, 101, 65, 101}, resend.lastBox());

        resend.forget();

        assertFalse(resend.pending(), "the last test's changes are not owed to the next");
        assertEquals(0, resend.lastBox().length, "and its box is not what a divergence arms");
        resend.rearm();
        assertFalse(resend.pending(), "so a stale divergence sends nothing");
        assertTrue(resend.due().isEmpty());
        assertTrue(resend.due().isEmpty());

        resend.noteBlock(5, 70, 5);
        resend.due();
        assertArrayEquals(new int[] {4, 68, 4, 3, 4, 3}, resend.due().get(0));
        assertArrayEquals(new int[] {4, 68, 4, 6, 71, 6}, resend.lastBox());
    }

    @Test
    void aChangeOutsideTheRoomIsNotOwed() {
        // The room build clears the whole test area, and a previous test's fixtures can reach its
        // edge. A client's own light at such a cell is not the room this test captures, and the
        // chunk may not even be one it received, so the wait must not compare it (#1695).
        GrugLightResend resend = new GrugLightResend();
        resend.bound(0, 60, 0, 15, 80, 15);

        resend.noteBlock(200, 70, 0);
        resend.noteBlock(0, 70, 200);

        assertFalse(resend.pending(), "a change outside the bound owes the client nothing");
        assertTrue(resend.due().isEmpty());
        assertTrue(resend.due().isEmpty());

        resend.noteBlock(5, 64, 5);
        resend.due();
        resend.due();
        assertArrayEquals(new int[] {4, 62, 4, 6, 65, 6}, resend.lastBox());
    }

    @Test
    void aBoundForgetsTheBoxOfTheLastRoom() {
        GrugLightResend resend = new GrugLightResend();
        resend.noteBlock(100, 64, 100);
        resend.due();
        resend.due();
        assertArrayEquals(new int[] {99, 62, 99, 101, 65, 101}, resend.lastBox());

        resend.bound(-35, 53, -80, -3, 104, -48);

        assertFalse(resend.pending(), "the last room's placements are not this room's");
        assertEquals(0, resend.lastBox().length, "and its box is not what a divergence arms");
        resend.noteBlock(-32, 63, -48);
        resend.due();
        resend.due();
        assertArrayEquals(new int[] {-33, 62, -49, -31, 65, -47}, resend.lastBox());
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
