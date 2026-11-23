package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Covers the checksum a settle ask sends for the blocks a test placed (#1664). */
class GrugBlockRegionTest {
    private static final GrugBlockRegion.Loaded ALL = (cx, cz) -> true;

    @Test
    void eachChunkGetsItsOwnBoxAndTheBoxIsForgottenOnceSettled() {
        GrugBlockRegion region = new GrugBlockRegion();
        assertEquals("", region.ask((x, y, z) -> 1, ALL));

        region.note(5, 64, 3);
        region.note(2, 70, 4);
        region.note(9, 60, 0);
        // A distant placement is its own box, not a bounding box over the chunks between.
        region.note(-100, 10, 200);
        GrugBlockRegion.Expected sent = GrugBlockRegion.decode(region.ask((x, y, z) -> 1, ALL));
        assertEquals(2, sent.boxes.size());
        assertArrayEquals(new int[] {2, 60, 0, 9, 70, 4}, sent.boxes.get(0));
        assertArrayEquals(new int[] {-100, 10, 200, -100, 10, 200}, sent.boxes.get(1));

        region.answeredSettled();
        assertEquals("", region.ask((x, y, z) -> 1, ALL));
    }

    @Test
    void aResetForgetsWhatAnEarlierTestPlaced() {
        GrugBlockRegion region = new GrugBlockRegion();
        region.note(1, 1, 1);
        region.reset();
        assertEquals("", region.ask((x, y, z) -> 1, ALL));
    }

    @Test
    void aChunkTheServerNoLongerHoldsIsLeftOutOfTheAsk() {
        GrugBlockRegion region = new GrugBlockRegion();
        region.note(1, 1, 1);
        region.note(40, 1, 1);
        GrugBlockRegion.Expected sent =
                GrugBlockRegion.decode(region.ask((x, y, z) -> 1, (cx, cz) -> cx == 2));
        assertEquals(1, sent.boxes.size());
        assertArrayEquals(new int[] {40, 1, 1, 40, 1, 1}, sent.boxes.get(0));
        assertEquals("", region.ask((x, y, z) -> 1, (cx, cz) -> false));
    }

    @Test
    void anAskStopsAtTheNameBudgetOldestFirst() {
        GrugBlockRegion region = new GrugBlockRegion();
        for (int chunk = 0; chunk < 40; chunk++) {
            region.note(chunk * 16, 100, 0);
        }
        String text = region.ask((x, y, z) -> 1, ALL);
        assertTrue(text.length() <= GrugControlWire.MAX_NAME, text);
        GrugBlockRegion.Expected sent = GrugBlockRegion.decode(text);
        assertTrue(sent.boxes.size() > 1 && sent.boxes.size() < 40);
        assertEquals(0, sent.boxes.get(0)[0]);
    }

    @Test
    void aLargeBoxIsHashedWholeAndAMissingIdChangesTheChecksum() {
        GrugBlockRegion region = new GrugBlockRegion();
        region.note(0, 0, 0);
        region.note(15, 127, 15);
        GrugBlockRegion.Expected sent =
                GrugBlockRegion.decode(region.ask((x, y, z) -> x == 15 && y == 127 ? 54 : 0, ALL));
        assertTrue(GrugBlockRegion.holds(sent, (x, y, z) -> x == 15 && y == 127 ? 54 : 0));
        assertFalse(GrugBlockRegion.holds(sent, (x, y, z) -> 0));
    }

    @Test
    void theClientHoldsTheBlocksOnlyWhenItsCopyHasTheServersIds() {
        GrugBlockRegion region = new GrugBlockRegion();
        region.note(0, 0, 0);
        region.note(1, 0, 1);
        GrugBlockRegion.Expected sent =
                GrugBlockRegion.decode(region.ask((x, y, z) -> x == 1 ? 54 : 0, ALL));

        assertTrue(GrugBlockRegion.holds(sent, (x, y, z) -> x == 1 ? 54 : 0));
        assertFalse(GrugBlockRegion.holds(sent, (x, y, z) -> 0));
        assertFalse(GrugBlockRegion.holds(sent, (x, y, z) -> x == 1 ? 55 : 0));
        // A chunk the client never received answers the absent id, which no server id equals.
        assertFalse(GrugBlockRegion.holds(sent, (x, y, z) -> GrugBlockRegion.ABSENT));
        assertTrue(GrugBlockRegion.holds(null, (x, y, z) -> 0));
    }

    @Test
    void theChecksumDependsOnWhereAnIdIs() {
        List<int[]> boxes = Collections.singletonList(new int[] {0, 0, 0, 1, 0, 0});
        assertNotEquals(
                GrugBlockRegion.checksum(boxes, (x, y, z) -> x == 0 ? 7 : 0),
                GrugBlockRegion.checksum(boxes, (x, y, z) -> x == 1 ? 7 : 0));
    }

    @Test
    void anAskRoundTripsNegativeCoordinatesAndAMalformedOneIsRefused() {
        List<int[]> boxes = new ArrayList<>();
        boxes.add(new int[] {-1, 2, -3, 4, 5, 6});
        boxes.add(new int[] {-30000000, 0, -30000000, -29999999, 1, -29999999});
        GrugBlockRegion.Expected back =
                GrugBlockRegion.decode(GrugBlockRegion.encode(boxes, 0xfedcba9876543210L));
        assertEquals(2, back.boxes.size());
        assertArrayEquals(boxes.get(0), back.boxes.get(0));
        assertArrayEquals(boxes.get(1), back.boxes.get(1));
        assertEquals(0xfedcba9876543210L, back.checksum);
        assertNull(GrugBlockRegion.decode(""));
        assertThrows(IllegalArgumentException.class, () -> GrugBlockRegion.decode("1,2,3;|0"));
        assertThrows(IllegalArgumentException.class, () -> GrugBlockRegion.decode("1,2,3,4,5,6;"));
    }
}
