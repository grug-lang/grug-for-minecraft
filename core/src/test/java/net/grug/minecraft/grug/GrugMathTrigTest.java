package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugMathTrigTest {
    @Test
    void sineAtTheQuarterTurns() {
        assertEquals(0.0, HostFunctions.Math_sin(0));
        assertEquals(1.0, HostFunctions.Math_sin(Math.PI / 2));
        assertEquals(0.0, HostFunctions.Math_sin(Math.PI), 1e-15);
        assertEquals(-1.0, HostFunctions.Math_sin(-Math.PI / 2));
    }

    @Test
    void cosineAtTheQuarterTurns() {
        assertEquals(1.0, HostFunctions.Math_cos(0));
        assertEquals(0.0, HostFunctions.Math_cos(Math.PI / 2), 1e-15);
        assertEquals(-1.0, HostFunctions.Math_cos(Math.PI));
        assertEquals(1.0, HostFunctions.Math_cos(-2 * Math.PI), 1e-15);
    }

    @Test
    void largeAnglesWrapAround() {
        assertEquals(StrictMath.sin(1e6), HostFunctions.Math_sin(1e6));
        assertEquals(StrictMath.cos(1e6), HostFunctions.Math_cos(1e6));
        assertEquals(Math.sin(0.5), HostFunctions.Math_sin(0.5 + 100 * 2 * Math.PI), 1e-9);
    }

    @Test
    void nonFiniteAnglesAreNaN() {
        assertTrue(Double.isNaN(HostFunctions.Math_sin(Double.POSITIVE_INFINITY)));
        assertTrue(Double.isNaN(HostFunctions.Math_cos(Double.NaN)));
    }
}
