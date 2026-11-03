package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrugControlFaultsTest {
    @Test
    void anUnsetOrBlankValueIsTheFallbackAndANumberIsParsed() {
        assertEquals(-1, GrugControlFaults.parseInt(null, -1));
        assertEquals(-1, GrugControlFaults.parseInt("  ", -1));
        assertEquals(7, GrugControlFaults.parseInt(" 7 ", -1));
        assertEquals(3, GrugControlFaults.intFromEnv("GRUG_NO_SUCH_VARIABLE_FOR_THE_TEST", 3));
    }
}
