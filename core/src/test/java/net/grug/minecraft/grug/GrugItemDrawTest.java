package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Random;

/** Covers how a frame pass's stack becomes copies and how those copies are scattered. */
class GrugItemDrawTest {

    private static final Object ITEM = new Object();

    @Test
    void aDrawCarriesTheStackAndWhereToPutIt() {
        GrugItemDraw draw = new GrugItemDraw(ITEM, 3, 7, 0.25, 0.5, 0.75);

        assertEquals(ITEM, draw.item());
        assertEquals(3, draw.damage());
        assertEquals(7, draw.count());
        assertEquals(0.25, draw.x());
        assertEquals(0.5, draw.y());
        assertEquals(0.75, draw.z());
    }

    @Test
    void aStackOfNoneDrawsNoCopies() {
        assertEquals(0, new GrugItemDraw(ITEM, 0, 0, 0, 0, 0).copies());
        assertEquals(0, new GrugItemDraw(ITEM, 0, -1, 0, 0, 0).copies());
    }

    @Test
    void theCopyCountIsTheReferencesOwnThresholds() {
        // More than 1 is 2 copies, more than 5 is 3, more than 20 is 4, and the rest is one.
        assertEquals(1, copies(1));
        assertEquals(2, copies(2));
        assertEquals(2, copies(5));
        assertEquals(3, copies(6));
        assertEquals(3, copies(20));
        assertEquals(4, copies(21));
        assertEquals(4, copies(64));
    }

    private static int copies(int count) {
        return new GrugItemDraw(ITEM, 0, count, 0, 0, 0).copies();
    }

    @Test
    void theFirstCopyIsNotScattered() {
        assertArrayEquals(new double[] {0.0, 0.0, 0.0}, GrugItemDraw.scatter(0));
        assertArrayEquals(new double[] {0.0, 0.0, 0.0}, GrugItemDraw.scatter(-1));
    }

    @Test
    void everyExtraCopyTakesTheNextThreeFloatsOfARandomSeededWithOneHundredAndEightySeven() {
        // The reference re-seeds one Random with 187 for every stack and consumes three nextFloat
        // calls per extra copy, so the second copy takes the first three floats and the fourth copy
        // the seventh through ninth. Pinning the arithmetic here is what keeps every loader's
        // stack scattered the same way.
        Random random = new Random(187L);
        for (int copy = 1; copy <= 3; copy++) {
            double[] expected = {
                random.nextFloat() * 2.0 - 1.0,
                random.nextFloat() * 2.0 - 1.0,
                random.nextFloat() * 2.0 - 1.0
            };
            assertArrayEquals(expected, GrugItemDraw.scatter(copy), "copy " + copy);
        }
    }

    @Test
    void theLargestWholeNumbersAndAnyFinitePositionAreUsable() {
        assertEquals(null, GrugItemDraw.problem(0, 0, 0.0, 0.0, 0.0));
        assertEquals(
                null, GrugItemDraw.problem(Integer.MAX_VALUE, Integer.MAX_VALUE, -1.5, 2.5, 1e6));
    }

    @Test
    void aDamageThatIsNegativeFractionalOrTooLargeIsNamed() {
        assertProblemNames("damage", GrugItemDraw.problem(-1, 1, 0, 0, 0));
        assertProblemNames("damage", GrugItemDraw.problem(1.5, 1, 0, 0, 0));
        assertProblemNames("damage", GrugItemDraw.problem(2147483648.0, 1, 0, 0, 0));
    }

    @Test
    void aCountThatIsNegativeFractionalOrTooLargeIsNamed() {
        assertProblemNames("count", GrugItemDraw.problem(0, -1, 0, 0, 0));
        assertProblemNames("count", GrugItemDraw.problem(0, 1.5, 0, 0, 0));
        assertProblemNames("count", GrugItemDraw.problem(0, 2147483648.0, 0, 0, 0));
    }

    @Test
    void aPositionThatIsNotFiniteIsNamedByItsAxis() {
        assertProblemNames("x", GrugItemDraw.problem(0, 1, Double.NaN, 0, 0));
        assertProblemNames("y", GrugItemDraw.problem(0, 1, 0, Double.POSITIVE_INFINITY, 0));
        assertProblemNames("z", GrugItemDraw.problem(0, 1, 0, 0, Double.NEGATIVE_INFINITY));
    }

    private static void assertProblemNames(String name, String problem) {
        assertTrue(problem != null, "A draw with a bad " + name + " has to be refused.");
        assertTrue(
                problem.contains("The " + name),
                "The refusal names the bad value as " + name + ": " + problem);
    }
}
