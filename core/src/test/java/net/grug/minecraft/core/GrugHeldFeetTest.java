package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The feet height a dedicated run holds its test player at (#1847).
 *
 * <p>Whether a height is held is a flag of its own, so a move to feet 0 is a real height and later
 * moves are still tracked. A snap saves the height it found, so a test's move is put back before
 * the next test's room is built. The b1.7.3-stationapi, 1.2.5-forge and a1.1.2_01-ornithe dedicated
 * runs all hold their player through this one class.
 */
class GrugHeldFeetTest {
    @Test
    void holdingFeetZeroIsARealHeight() {
        GrugHeldFeet held = new GrugHeldFeet();
        held.hold(0.0);

        assertTrue(held.isHeld(), "a run that starts with the player at feet 0 still holds them");
        assertEquals(0.0, held.feetY(), 0.0);
    }

    @Test
    void aMoveToFeetZeroIsHeldAndLaterMovesStillTrack() {
        GrugHeldFeet held = new GrugHeldFeet();
        held.hold(70.0);

        held.moveTo(0.0);

        assertTrue(held.isHeld(), "feet 0 is a height the run holds");
        assertEquals(0.0, held.feetY(), 0.0);
        held.moveTo(72.0);
        assertEquals(72.0, held.feetY(), 0.0, "the move after a move to 0 is still tracked");
    }

    @Test
    void aSnapSavesTheHeightAndTheRestorePutsItBack() {
        GrugHeldFeet held = new GrugHeldFeet();
        held.hold(70.0);

        held.saveForTest();
        held.moveTo(72.0);
        held.restoreAfterTest();

        assertEquals(
                70.0, held.feetY(), 0.0, "the next test's room is built around the saved height");
    }

    @Test
    void aRestoreWithNothingSavedChangesNothing() {
        GrugHeldFeet held = new GrugHeldFeet();
        held.hold(70.5);

        held.restoreAfterTest();

        assertEquals(70.5, held.feetY(), 0.0);
    }

    @Test
    void aMoveBeforeTheRunHoldsAHeightIsNotTracked() {
        GrugHeldFeet held = new GrugHeldFeet();

        assertFalse(held.isHeld());
        held.moveTo(72.0);

        assertFalse(held.isHeld());
        assertEquals(0.0, held.feetY(), 0.0);
    }

    @Test
    void aHeldHeightAndAMoveAreRoundedToAMillionth() {
        GrugHeldFeet held = new GrugHeldFeet();
        held.hold(70.5000000004);
        assertEquals(70.5, held.feetY(), 0.0);

        held.moveTo(72.0000000004);
        assertEquals(72.0, held.feetY(), 0.0);
    }

    @Test
    void aRoomIsNeverAnchoredBelowTheHeldHeight() {
        GrugHeldFeet held = new GrugHeldFeet();
        assertEquals(69.8, held.buildAnchor(69.8), 0.0, "no held height yet");

        held.hold(70.0);
        assertEquals(70.0, held.buildAnchor(69.8), 0.0);
        assertEquals(71.3, held.buildAnchor(71.3), 0.0);
    }
}
