package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugQuietCountTest {
    @Test
    void theCountMustHoldForTheQuietTicksAndReachTheMinimum() {
        GrugQuietCount quiet = new GrugQuietCount(3, 9);

        assertFalse(quiet.tick(4));
        assertFalse(quiet.tick(4));
        assertFalse(quiet.tick(4));
        assertFalse(quiet.tick(4));
        assertFalse(quiet.tick(9));
        assertFalse(quiet.tick(9));
        assertFalse(quiet.tick(9));
        assertTrue(quiet.tick(9));
        assertTrue(quiet.tick(9));
    }

    @Test
    void aChangeRestartsTheWait() {
        GrugQuietCount quiet = new GrugQuietCount(2, 1);

        assertFalse(quiet.tick(5));
        assertFalse(quiet.tick(5));
        assertTrue(quiet.tick(5));
        assertFalse(quiet.tick(6));
        assertFalse(quiet.tick(6));
        assertTrue(quiet.tick(6));
    }
}
