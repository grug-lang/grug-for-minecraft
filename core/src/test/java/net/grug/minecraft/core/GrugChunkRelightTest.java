package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class GrugChunkRelightTest {

    private static int[] heights(int height) {
        int[] columns = new int[GrugChunkRelight.COLUMNS * GrugChunkRelight.COLUMNS];
        Arrays.fill(columns, height);
        return columns;
    }

    @Test
    void noBoxWhenNoColumnMoved() {
        // A packet of blocks whose columns keep their height, which is a rebuild of a room already
        // there: its light bytes are the server's, and nothing has to be recomputed.
        assertNull(GrugChunkRelight.box(heights(78), heights(78), 0, 0));
    }

    @Test
    void aRaisedColumnSweepsFromItsOldToItsNewHeightAroundIt() {
        int[] before = heights(78);
        int[] after = heights(78);
        after[GrugChunkRelight.column(10, 4)] = 91;
        // The cell just above the old height is the one a region packet leaves stale, and the four
        // columns beside the changed one take their light from it, so the box reaches one out.
        // World x 10 and z 20 fall in chunk (0, 1) at local (10, 4).
        assertArrayEquals(
                new int[] {9, 78, 19, 11, 91, 21}, GrugChunkRelight.box(before, after, 0, 1));
    }

    @Test
    void aLoweredColumnSweepsDownToItsNewHeight() {
        int[] before = heights(91);
        int[] after = heights(91);
        after[GrugChunkRelight.column(10, 4)] = 78;
        assertArrayEquals(
                new int[] {9, 78, 19, 11, 91, 21}, GrugChunkRelight.box(before, after, 0, 1));
    }

    @Test
    void aNegativeChunkKeepsItsWorldCoordinates() {
        // The failing cell of #1740: chunk (-2, -4) at local (13, 12) is world (-19, -52).
        int[] before = heights(77);
        int[] after = heights(77);
        after[GrugChunkRelight.column(13, 12)] = 91;
        assertArrayEquals(
                new int[] {-20, 77, -53, -18, 91, -51},
                GrugChunkRelight.box(before, after, -2, -4));
    }

    @Test
    void everyMovedColumnSharesOneBox() {
        int[] before = heights(78);
        int[] after = heights(78);
        after[GrugChunkRelight.column(10, 4)] = 91;
        after[GrugChunkRelight.column(12, 5)] = 70;
        after[GrugChunkRelight.column(11, 4)] = 90;
        assertArrayEquals(
                new int[] {9, 70, 19, 13, 91, 22}, GrugChunkRelight.box(before, after, 0, 1));
    }

    @Test
    void aHeightAtTheWorldsTopStaysInsideIt() {
        int[] before = heights(127);
        int[] after = heights(127);
        after[GrugChunkRelight.column(0, 0)] = 128;
        // A height counts the blocks below it, so 128 means the top cell is block 127 and there is
        // no cell above it to recompute.
        assertArrayEquals(
                new int[] {-1, 127, -1, 1, 127, 1}, GrugChunkRelight.box(before, after, 0, 0));
    }

    @Test
    void aWholeColumnPacketNeedsNoSweep() {
        // The first delivery of a chunk: it carries the server's light for every cell of the
        // column.
        assertTrue(GrugChunkRelight.isWholeColumn(0, 128));
        assertTrue(GrugChunkRelight.isWholeColumn(-4, 140));
        // A region packet carries only its own layers, however tall it is.
        assertFalse(GrugChunkRelight.isWholeColumn(0, 127));
        assertFalse(GrugChunkRelight.isWholeColumn(1, 128));
        assertFalse(GrugChunkRelight.isWholeColumn(89, 3));
    }

    @Test
    void theColumnIndexIsTheGamesOwn() {
        assertEquals(13, GrugChunkRelight.column(13, 0));
        assertEquals(4 * 16 + 13, GrugChunkRelight.column(13, 4));
    }

    @Test
    void aHeightMapOfTheWrongSizeIsAProgrammingError() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugChunkRelight.box(new int[255], heights(0), 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugChunkRelight.box(heights(0), new int[255], 0, 0));
    }
}
