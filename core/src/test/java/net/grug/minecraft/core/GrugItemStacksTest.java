package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Covers the null-stack read in {@link GrugItemStacks}. */
class GrugItemStacksTest {

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
}
