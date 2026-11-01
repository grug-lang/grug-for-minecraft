package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Covers the null-stack read in {@link GrugItemStacks}. */
class GrugItemStacksTest {

    /** A stack whose damage the test reads without a game. */
    private static final class FakeStack {
        private final double damage;

        FakeStack(double damage) {
            this.damage = damage;
        }
    }

    @Test
    void aMissingStackCarriesNoDamage() {
        assertEquals(
                0.0,
                GrugItemStacks.damageOrZero(
                        null,
                        stack -> {
                            throw new AssertionError("a null stack is never read");
                        }));
    }

    @Test
    void aStacksOwnDamageIsAnswered() {
        assertEquals(
                7.0, GrugItemStacks.damageOrZero(new FakeStack(7.0), s -> ((FakeStack) s).damage));
    }
}
