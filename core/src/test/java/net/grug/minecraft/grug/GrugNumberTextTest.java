package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrugNumberTextTest {
    @Test
    void wholeNumbersPrintAsIntegers() {
        assertEquals("0", HostFunctions.numberText(0));
        assertEquals("0", HostFunctions.numberText(-0.0));
        assertEquals("41", HostFunctions.numberText(41));
        assertEquals("-7", HostFunctions.numberText(-7));
        assertEquals("10000000", HostFunctions.numberText(1e7));
        assertEquals("100000000000000000000", HostFunctions.numberText(1e20));
        assertEquals("-100000000000000000000", HostFunctions.numberText(-1e20));
    }

    @Test
    void fractionsPrintAsJavasShortestText() {
        assertEquals("2.5", HostFunctions.numberText(2.5));
        assertEquals("-0.1", HostFunctions.numberText(-0.1));
        assertEquals("1.0E-4", HostFunctions.numberText(1e-4));
    }

    @Test
    void nonFiniteNumbersPrintTheirNames() {
        assertEquals("NaN", HostFunctions.numberText(Double.NaN));
        assertEquals("Infinity", HostFunctions.numberText(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", HostFunctions.numberText(Double.NEGATIVE_INFINITY));
    }

    @Test
    void hostFunctionAnswersTheSameText() {
        assertEquals("41", HostFunctions.String_from_number(41));
    }
}
