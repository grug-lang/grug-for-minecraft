package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    /**
     * The generated adapter unwraps an {@code Option[number]} as a {@link Double}, so a float
     * answer has to be widened when the Option is made. Boxing the parsed {@link Float} itself made
     * every {@code String.to_float(text).unwrap()} throw a {@code ClassCastException} in the game,
     * which no smoketest run could see because its Option holds every number as a double.
     */
    @Test
    void toFloatAnswersAWidenedFloatSoAnOptionNumberUnwraps() {
        long option = HostFunctions.String_to_float("0.1");
        assertEquals(Double.valueOf(0.10000000149011612), HostFunctions.Option_unwrap(option));
    }

    @Test
    void toFloatAnswersAnEmptyOptionForTextThatIsNoFloat() {
        assertFalse(HostFunctions.Option_has(HostFunctions.String_to_float("0.1.0")));
    }
}
