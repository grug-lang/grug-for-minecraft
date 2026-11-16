package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugStarterPlacementTest {
    @Test
    void noStarterIsNeverPlaced() {
        assertFalse(new GrugStarterPlacement().shouldPlace(null));
    }

    @Test
    void aStarterIsPlacedOnce() {
        GrugStarterPlacement placement = new GrugStarterPlacement();
        Object starter = new Object();
        assertTrue(placement.shouldPlace(starter));
        assertFalse(placement.shouldPlace(starter));
    }

    @Test
    void aSecondStarterIsPlacedAfterTheFirstLeft() {
        GrugStarterPlacement placement = new GrugStarterPlacement();
        Object first = new Object();
        Object second = new Object();
        assertTrue(placement.shouldPlace(first));
        assertFalse(placement.shouldPlace(null));
        assertTrue(placement.shouldPlace(second));
        assertFalse(placement.shouldPlace(second));
    }

    @Test
    void aStarterWhoSeesTheGapIsNotPlacedTwice() {
        GrugStarterPlacement placement = new GrugStarterPlacement();
        Object starter = new Object();
        assertTrue(placement.shouldPlace(starter));
        assertFalse(placement.shouldPlace(null));
        assertFalse(placement.shouldPlace(starter));
    }
}
