package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The rule that picks the test and the tick at which the runner forces normalized normals. */
class GrugForcedNormalizationTest {
    private static final String MARKED = "jei/tests/forced_normalization_item_list-Test.grug";

    @Test
    void aMarkedTestIsDueAtExactlyTheOneTick() {
        assertFalse(GrugForcedNormalization.isDue(MARKED, GrugForcedNormalization.TICK - 1, false));
        assertTrue(GrugForcedNormalization.isDue(MARKED, GrugForcedNormalization.TICK, false));
        assertFalse(GrugForcedNormalization.isDue(MARKED, GrugForcedNormalization.TICK + 1, false));
    }

    @Test
    void anUnmarkedTestIsNeverDue() {
        assertFalse(
                GrugForcedNormalization.isDue(
                        "jei/tests/item_list-Test.grug", GrugForcedNormalization.TICK, false));
        assertFalse(
                GrugForcedNormalization.isDue(
                        "jei/tests/my_forced_normalization_x-Test.grug",
                        GrugForcedNormalization.TICK,
                        false));
        assertFalse(GrugForcedNormalization.isDue("", GrugForcedNormalization.TICK, false));
    }

    @Test
    void thePrefixHasToStartTheFileName() {
        assertTrue(
                GrugForcedNormalization.isDue(
                        "/a/b/forced_normalization_item_list-Test.grug",
                        GrugForcedNormalization.TICK,
                        false));
        assertFalse(
                GrugForcedNormalization.isDue(
                        "/a/b/xforced_normalization_item_list-Test.grug",
                        GrugForcedNormalization.TICK,
                        false));
    }

    @Test
    void aReferenceRunIsNeverDue() {
        assertFalse(GrugForcedNormalization.isDue(MARKED, GrugForcedNormalization.TICK, true));
    }
}
