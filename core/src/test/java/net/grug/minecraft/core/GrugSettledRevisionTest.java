package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The decision a forwarded settle wait makes between asking the client again and answering from the
 * revision it already answered for (#1086). Pure, so every combination is covered here rather than
 * only the one a run's timing happens to reach.
 */
class GrugSettledRevisionTest {
    @Test
    void anAnswerHoldsOnlyForTheRevisionItWasGivenAt() {
        GrugSettledRevision settled = new GrugSettledRevision();
        assertFalse(settled.answeredSettled(1), "nothing has been answered yet");

        settled.record(7, true);
        assertTrue(settled.answeredSettled(7));
        assertFalse(settled.answeredSettled(8), "a later revision has not been answered");
        assertFalse(settled.answeredSettled(6), "an earlier revision has been left behind");

        settled.record(8, false);
        assertFalse(settled.answeredSettled(8), "an unsettled answer is not a settled one");
        assertFalse(settled.answeredSettled(7), "the revision moved with the answer");
    }

    @Test
    void forgettingAnAnswerAsksAgainAtTheSameRevision() {
        GrugSettledRevision settled = new GrugSettledRevision();
        settled.record(3, true);
        assertTrue(settled.answeredSettled(3));

        settled.forget();
        assertFalse(settled.answeredSettled(3));
    }
}
