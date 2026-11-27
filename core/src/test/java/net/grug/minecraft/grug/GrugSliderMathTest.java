package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The slider's arithmetic, which is a pure function of the values the game reads so the coverage
 * gate can be pinned by tests rather than by a drag that happens to land on a branch.
 */
class GrugSliderMathTest {

    @Test
    void aRangeNeedsTwoFiniteEndsWithTheMaxAboveTheMin() {
        assertNull(GrugSliderMath.rangeProblem(0, 10));
        assertNull(GrugSliderMath.rangeProblem(-5, -1));

        String infinite = GrugSliderMath.rangeProblem(0, Double.POSITIVE_INFINITY);
        assertNotNull(infinite);
        assertTrue(infinite.contains("finite"));
        assertNotNull(GrugSliderMath.rangeProblem(Double.NaN, 1));

        String equal = GrugSliderMath.rangeProblem(5, 5);
        assertNotNull(equal);
        assertTrue(equal.contains("greater than the min"));
        assertNotNull(GrugSliderMath.rangeProblem(6, 5));
    }

    @Test
    void theFractionIsTheValuesPlaceOnTheTrack() {
        assertEquals(0.0, GrugSliderMath.fraction(0, 0, 10));
        assertEquals(0.5, GrugSliderMath.fraction(5, 0, 10));
        assertEquals(1.0, GrugSliderMath.fraction(10, 0, 10));
        assertEquals(
                2.0, GrugSliderMath.fraction(20, 0, 10), "a value past the end is not clamped");
        assertEquals(-1.0, GrugSliderMath.fraction(-10, 0, 10));
        assertEquals(0.25, GrugSliderMath.fraction(2.5, 0, 10));
    }

    @Test
    void theValueIsWhatAFractionOfTheTrackNames() {
        assertEquals(0.0, GrugSliderMath.value(0, 0, 10));
        assertEquals(5.0, GrugSliderMath.value(0.5, 0, 10));
        assertEquals(-2.5, GrugSliderMath.value(0.5, -5, 0));
        assertEquals(20.0, GrugSliderMath.value(2.0, 0, 10));
    }

    @Test
    void onlyTheDrawingIsClampedAndANonNumberDrawsAtTheStart() {
        assertEquals(0.5, GrugSliderMath.drawnFraction(0.5));
        assertEquals(1.0, GrugSliderMath.drawnFraction(2.0));
        assertEquals(0.0, GrugSliderMath.drawnFraction(-1.0));
        assertEquals(0.0, GrugSliderMath.drawnFraction(Double.NaN));
    }

    @Test
    void aValueIsBroughtIntoAUsableRangeAndAnUnusableOneIsLeftAlone() {
        assertEquals(5.0, GrugSliderMath.clamp(5, 0, 10));
        assertEquals(0.0, GrugSliderMath.clamp(-1, 0, 10));
        assertEquals(10.0, GrugSliderMath.clamp(99, 0, 10));
        // A range that is not usable is refused before any value is applied to it, so the drawn
        // value is the one the slider already holds rather than a clamp to a broken track.
        assertEquals(5.0, GrugSliderMath.clamp(5, Double.NaN, 10));
        assertEquals(5.0, GrugSliderMath.clamp(5, 0, Double.POSITIVE_INFINITY));
        assertEquals(5.0, GrugSliderMath.clamp(5, 5, 5));
        assertEquals(5.0, GrugSliderMath.clamp(5, 10, 0));
    }

    @Test
    void theLabelRoundsTheValueToTenthsAsJavaPrintsIt() {
        assertEquals("level: 3.5", GrugSliderMath.label("level", 3.5));
        assertEquals("level: 3.5", GrugSliderMath.label("level", 3.54));
        assertEquals("level: 3.6", GrugSliderMath.label("level", 3.55));
        assertEquals("level: 0.0", GrugSliderMath.label("level", -0.04));
        assertEquals("speed: 1.0", GrugSliderMath.label("speed", 1.0));
        assertEquals(": 0.0", GrugSliderMath.label("", 0.0));
    }

    @Test
    void aRoundTripThroughTheFractionKeepsTheValue() {
        double[] values = {-5.5, -1.0, 0.0, 0.25, 3.5, 9.75, 10.0, 12.0};
        for (double value : values) {
            double fraction = GrugSliderMath.fraction(value, -5.5, 12.0);
            assertEquals(value, GrugSliderMath.value(fraction, -5.5, 12.0), 1e-12);
        }
    }
}
