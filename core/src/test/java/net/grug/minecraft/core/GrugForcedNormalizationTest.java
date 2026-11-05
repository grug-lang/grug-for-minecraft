package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** The rule that picks the test the runner forces normalized normals for. */
class GrugForcedNormalizationTest {
    private static final String MARKED = "jei/tests/item_list_forced_normalization-Test.grug";

    @Test
    void aMarkedTestIsDue() {
        assertTrue(GrugForcedNormalization.appliesTo(MARKED, false, false));
    }

    @Test
    void anUnmarkedTestIsNeverDue() {
        assertFalse(
                GrugForcedNormalization.appliesTo("jei/tests/item_list-Test.grug", false, false));
        assertFalse(
                GrugForcedNormalization.appliesTo(
                        "jei/tests/item_list_forced_normalization_extra-Test.grug", false, false));
        assertFalse(GrugForcedNormalization.appliesTo("", false, false));
    }

    @Test
    void theMarkHasToEndTheStem() {
        assertFalse(
                GrugForcedNormalization.appliesTo(
                        "/a/b/xforced_normalization-Test.grug", false, false));
        assertTrue(
                GrugForcedNormalization.appliesTo(
                        "/a/b/item_list_forced_normalization-Test.grug", false, false));
    }

    @Test
    void aReferenceRunIsNeverDue() {
        assertFalse(GrugForcedNormalization.appliesTo(MARKED, false, true));
    }

    @Test
    void aDedicatedServerIsNeverDue() {
        assertFalse(GrugForcedNormalization.appliesTo(MARKED, true, false));
    }

    @Test
    void aRestoreTurnsOffOnlyTheCapabilitiesTheForceTurnedOn() {
        // The two constants stand in for GL_NORMALIZE and GL_RESCALE_NORMAL, which the loaders pass
        // in themselves: this rule does not interpret them.
        int normalize = 0x0BA1;
        int rescaleNormal = 0x803A;
        // Both were off, so the force turned both on and the restore turns both off.
        assertEquals(
                List.of(normalize, rescaleNormal),
                GrugForcedNormalization.capabilitiesToTurnOff(
                        false, false, normalize, rescaleNormal));
        // A capability the game itself left on stays on, whichever one it is: turning it off would
        // put back a state the force never found.
        assertEquals(
                List.of(normalize),
                GrugForcedNormalization.capabilitiesToTurnOff(
                        false, true, normalize, rescaleNormal));
        assertEquals(
                List.of(rescaleNormal),
                GrugForcedNormalization.capabilitiesToTurnOff(
                        true, false, normalize, rescaleNormal));
        assertEquals(
                List.of(),
                GrugForcedNormalization.capabilitiesToTurnOff(
                        true, true, normalize, rescaleNormal));
    }

    @Test
    void aRestoreMatchesOnlyWhenBothCapabilitiesAreWhatTheForceFound() {
        // A restore that left one capability on, or turned one off that the game itself had on,
        // does not match, so the runner can fail the marked test on the answer alone. The cases
        // take both answers of each comparison, so no arm is left for the gate.
        assertTrue(GrugForcedNormalization.matchesFound(false, false, false, false));
        assertTrue(GrugForcedNormalization.matchesFound(true, true, true, true));
        assertFalse(GrugForcedNormalization.matchesFound(true, false, false, false));
        assertFalse(GrugForcedNormalization.matchesFound(false, true, true, true));
        assertFalse(GrugForcedNormalization.matchesFound(false, false, true, true));
        assertFalse(GrugForcedNormalization.matchesFound(true, true, false, true));
    }
}
