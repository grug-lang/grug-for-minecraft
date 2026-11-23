package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the checksum a settle ask sends for the blocks a test placed (#1664). */
class GrugBlockRegionTest {

    @Test
    void theBoxGrowsToEveryPlacementAndIsForgottenOnceSettled() {
        GrugBlockRegion region = new GrugBlockRegion();
        assertEquals("", region.ask((x, y, z) -> 1));

        region.note(5, 64, -3);
        region.note(2, 70, 4);
        region.note(9, 60, 0);
        GrugBlockRegion.Expected sent = GrugBlockRegion.decode(region.ask((x, y, z) -> 1));
        assertArrayEquals(new int[] {2, 60, -3, 9, 70, 4}, sent.box);

        region.answeredSettled();
        assertEquals("", region.ask((x, y, z) -> 1));
    }

    @Test
    void theClientHoldsTheBlocksOnlyWhenItsCopyHasTheServersIds() {
        GrugBlockRegion region = new GrugBlockRegion();
        region.note(0, 0, 0);
        region.note(1, 0, 1);
        GrugBlockRegion.Expected sent =
                GrugBlockRegion.decode(region.ask((x, y, z) -> x == 1 ? 54 : 0));

        assertTrue(GrugBlockRegion.holds(sent, (x, y, z) -> x == 1 ? 54 : 0));
        // The neighbour's block is missing, or a different one, or the whole chunk is.
        assertFalse(GrugBlockRegion.holds(sent, (x, y, z) -> 0));
        assertFalse(GrugBlockRegion.holds(sent, (x, y, z) -> x == 1 ? 55 : 0));
        // No expectation is held, as for an ask with no placements.
        assertTrue(GrugBlockRegion.holds(null, (x, y, z) -> 0));
    }

    @Test
    void theChecksumDependsOnWhereAnIdIs() {
        int[] box = {0, 0, 0, 1, 0, 0};
        assertNotEquals(
                GrugBlockRegion.checksum(box, (x, y, z) -> x == 0 ? 7 : 0),
                GrugBlockRegion.checksum(box, (x, y, z) -> x == 1 ? 7 : 0));
    }

    @Test
    void anAskRoundTripsAndAMalformedOneIsRefused() {
        int[] box = {-1, 2, -3, 4, 5, 6};
        GrugBlockRegion.Expected back =
                GrugBlockRegion.decode(GrugBlockRegion.encode(box, 0xfedcba9876543210L));
        assertArrayEquals(box, back.box);
        assertEquals(0xfedcba9876543210L, back.checksum);
        assertNull(GrugBlockRegion.decode(""));
        assertThrows(IllegalArgumentException.class, () -> GrugBlockRegion.decode("1,2,3"));
    }
}
