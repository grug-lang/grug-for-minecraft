package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the count {@code item_stack} refuses, which a Java test can drive without the game's
 * adapter: every refusal asks for a number no stack can hold.
 */
class HostFunctionsItemStackTest {

    @Test
    void aWholeCountOfAtLeastOneIsAccepted() {
        assertNull(HostFunctions.itemStackCountProblem(1));
        assertNull(HostFunctions.itemStackCountProblem(5));
        assertNull(HostFunctions.itemStackCountProblem(64));
        // A mod that makes stacks larger than a vanilla slot holds keeps working: the game's own
        // constructor takes any int.
        assertNull(HostFunctions.itemStackCountProblem(Integer.MAX_VALUE));
    }

    @Test
    void aCountNoStackCanHoldIsRefused() {
        assertTrue(HostFunctions.itemStackCountProblem(0).contains("from 1 to"));
        assertTrue(HostFunctions.itemStackCountProblem(-1).contains("from 1 to"));
        assertTrue(HostFunctions.itemStackCountProblem(2147483648.0).contains("from 1 to"));
        assertTrue(HostFunctions.itemStackCountProblem(1.5).contains("from 1 to"));
        // NaN compares false against both ends of the range, so the fraction check is what catches
        // it, and the cast to int would otherwise turn it into 0.
        assertTrue(HostFunctions.itemStackCountProblem(Double.NaN).contains("from 1 to"));
    }
}
