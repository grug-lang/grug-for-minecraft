package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the null-stack read and the count rule in {@link GrugItemStacks}, the rule at each limit a
 * loader's save has: 127 where the count is one NBT byte, and an int on 1.20.6.
 */
class GrugItemStacksTest {

    /** The largest count 1.2.5, Alpha and both b1.7.3 loaders save, since they write a byte. */
    private static final int BYTE_SAVE_LIMIT = Byte.MAX_VALUE;

    /** A stack whose own number the test reads without a game. */
    private static final class FakeStack {
        private final double number;

        FakeStack(double number) {
            this.number = number;
        }
    }

    @Test
    void aMissingStackCarriesNoNumber() {
        assertEquals(
                0.0,
                GrugItemStacks.numberOrZero(
                        null,
                        stack -> {
                            throw new AssertionError("a null stack is never read");
                        }));
    }

    @Test
    void aStacksOwnNumberIsAnswered() {
        assertEquals(
                7.0, GrugItemStacks.numberOrZero(new FakeStack(7.0), s -> ((FakeStack) s).number));
    }

    @Test
    void aCountTheLoadersSaveKeepsFits() {
        assertTrue(GrugItemStacks.countFits(1, BYTE_SAVE_LIMIT));
        assertTrue(GrugItemStacks.countFits(5, BYTE_SAVE_LIMIT));
        assertTrue(GrugItemStacks.countFits(64, BYTE_SAVE_LIMIT));
        assertTrue(GrugItemStacks.countFits(BYTE_SAVE_LIMIT, BYTE_SAVE_LIMIT));
        // The same count is fine on a loader whose save keeps an int, so the limit is the save's
        // and not a fixed number.
        assertTrue(GrugItemStacks.countFits(128, Integer.MAX_VALUE));
        assertTrue(GrugItemStacks.countFits(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void aCountPastTheLoadersSaveDoesNotFit() {
        assertFalse(GrugItemStacks.countFits(128, BYTE_SAVE_LIMIT));
        assertFalse(GrugItemStacks.countFits(2147483648.0, Integer.MAX_VALUE));
    }

    @Test
    void aCountNoStackCanHoldDoesNotFit() {
        assertFalse(GrugItemStacks.countFits(0, BYTE_SAVE_LIMIT));
        assertFalse(GrugItemStacks.countFits(-1, BYTE_SAVE_LIMIT));
        assertFalse(GrugItemStacks.countFits(1.5, BYTE_SAVE_LIMIT));
        // NaN compares false against both ends of the range, so the fraction check is what catches
        // it, and the cast to int would otherwise turn it into 0.
        assertFalse(GrugItemStacks.countFits(Double.NaN, BYTE_SAVE_LIMIT));
        assertFalse(GrugItemStacks.countFits(0, Integer.MAX_VALUE));
    }

    @Test
    void theProblemNamesTheLoadersLargestCount() {
        assertNull(GrugItemStacks.countProblem(BYTE_SAVE_LIMIT, BYTE_SAVE_LIMIT));
        assertNull(GrugItemStacks.countProblem(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(GrugItemStacks.countProblem(128, BYTE_SAVE_LIMIT).contains("from 1 to 127"));
        assertTrue(
                GrugItemStacks.countProblem(2147483648.0, Integer.MAX_VALUE)
                        .contains("from 1 to 2147483647"));
        assertTrue(GrugItemStacks.countProblem(1.5, BYTE_SAVE_LIMIT).contains("from 1 to 127"));
    }
}
