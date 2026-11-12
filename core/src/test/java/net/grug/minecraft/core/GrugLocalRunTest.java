package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link GrugLocalRun}'s decision, not its call site: the 1.20.6 classes are excluded from
 * the coverage report, so no unit test and no grug test reaches the client tick that asks this. A
 * local run's integrated server cannot be dropped from a test either, so this decision is the whole
 * proof that a run which loses its server ends, rather than leaving the window, the dither, the
 * particles, the tutorial, the view and the cursor behind for the client's life (#1276).
 */
class GrugLocalRunTest {

    @Test
    void aLocalRunEndsOnlyWhenItsServerGoesAway() {
        assertFalse(
                GrugLocalRun.endsOnLostServer(true, true),
                "a run whose integrated server still exists keeps running");
        assertTrue(
                GrugLocalRun.endsOnLostServer(false, true),
                "a run that lost its integrated server can never advance again");
    }

    @Test
    void aRunThatIsNotRunningIsNotEnded() {
        assertFalse(
                GrugLocalRun.endsOnLostServer(true, false),
                "a client with no run has nothing to end, even with a server");
        assertFalse(
                GrugLocalRun.endsOnLostServer(false, false),
                "a client with no run has nothing to end, with or without a server");
    }
}
