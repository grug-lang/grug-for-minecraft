package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The rule that picks the test and the tick at which the runner forces one save. See #1132. */
class GrugForcedAutosaveTest {
    @Test
    void aMarkedTestIsDueAtExactlyTheOneTick() {
        String marked = "coverage/tests/autosave_save_indicator-Test.grug";
        assertFalse(GrugForcedAutosave.isDue(marked, GrugForcedAutosave.TICK - 1));
        assertTrue(GrugForcedAutosave.isDue(marked, GrugForcedAutosave.TICK));
        assertFalse(GrugForcedAutosave.isDue(marked, GrugForcedAutosave.TICK + 1));
    }

    @Test
    void anUnmarkedTestIsNeverDue() {
        assertFalse(
                GrugForcedAutosave.isDue(
                        "coverage/tests/coverage_save_indicator-Test.grug",
                        GrugForcedAutosave.TICK));
        assertFalse(
                GrugForcedAutosave.isDue(
                        "coverage/tests/my_autosave_x-Test.grug", GrugForcedAutosave.TICK));
        assertFalse(GrugForcedAutosave.isDue("", GrugForcedAutosave.TICK));
    }

    @Test
    void thePrefixHasToStartTheFileName() {
        assertTrue(
                GrugForcedAutosave.isDue(
                        "/a/b/autosave_save_indicator-Test.grug", GrugForcedAutosave.TICK));
        assertFalse(
                GrugForcedAutosave.isDue(
                        "/a/b/xautosave_save_indicator-Test.grug", GrugForcedAutosave.TICK));
    }
}
