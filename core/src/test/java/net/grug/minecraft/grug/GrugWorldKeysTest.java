package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the press detection a loader's poll of the held keys goes through. */
class GrugWorldKeysTest {

    @Test
    void aKeyIsPressedOnceHoweverLongItIsHeld() {
        GrugWorldKeys keys = new GrugWorldKeys();
        assertFalse(keys.pressed('L', false), "A key that is up is no press.");
        assertTrue(keys.pressed('L', true), "The first poll that sees it down is the press.");
        assertFalse(keys.pressed('L', true), "A held key is not a press again.");
        assertFalse(keys.pressed('L', false), "Letting go is no press.");
        assertTrue(keys.pressed('L', true), "Pressing it again is a new press.");
    }

    @Test
    void aKeyHeldThroughAScreenIsNotDelivered() {
        GrugWorldKeys keys = new GrugWorldKeys();
        assertFalse(
                keys.deliver('E', true, false), "Pressed while a screen is open: not delivered.");
        assertFalse(keys.deliver('E', true, true), "Still held when the screen closed: no press.");
        assertFalse(keys.deliver('E', false, true));
        assertTrue(keys.deliver('E', true, true), "A fresh press with no screen is delivered.");
    }

    @Test
    void everyKeyKeepsItsOwnState() {
        GrugWorldKeys keys = new GrugWorldKeys();
        assertTrue(keys.pressed('0', true));
        assertTrue(keys.pressed('Z', true));
        assertTrue(keys.pressed('A', true));
        assertFalse(keys.pressed('0', true));
    }

    @Test
    void onlyLettersAndDigitsAreTracked() {
        assertTrue(GrugWorldKeys.tracked('0'));
        assertTrue(GrugWorldKeys.tracked('9'));
        assertTrue(GrugWorldKeys.tracked('A'));
        assertTrue(GrugWorldKeys.tracked('Z'));
        assertFalse(GrugWorldKeys.tracked('/'));
        assertFalse(GrugWorldKeys.tracked(':'));
        assertFalse(GrugWorldKeys.tracked('@'));
        assertFalse(GrugWorldKeys.tracked('['));
        assertFalse(GrugWorldKeys.tracked('a'));
    }
}
