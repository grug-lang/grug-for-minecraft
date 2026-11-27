package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers {@link GrugHeldKeys}: a press is a repeat only while that same key is still held. */
class GrugHeldKeysTest {

    @Test
    void anotherHeldKeyDoesNotTurnAStillHeldKeyIntoAFreshPress() {
        GrugHeldKeys keys = new GrugHeldKeys();

        assertFalse(keys.press('A'), "the first press of A is not a repeat");
        assertTrue(keys.press('A'), "a second press of A while it is held is a repeat");
        assertFalse(keys.press('B'), "the first press of B is not a repeat");
        assertTrue(keys.press('A'), "B being held must not erase A");
        assertTrue(keys.press('B'), "A being held must not erase B");

        keys.release('A');
        assertFalse(keys.press('A'), "A is a fresh press again after its release");
        assertTrue(keys.press('B'), "B is still held, so its press is still a repeat");
    }

    @Test
    void aNewScreenStartsWithNoKeyHeld() {
        GrugHeldKeys keys = new GrugHeldKeys();
        keys.press('A');
        keys.press('B');

        keys.clear();

        assertFalse(keys.press('A'), "the first press after a clear is not a repeat");
        assertFalse(keys.press('B'), "no key is held after a clear");
    }
}
