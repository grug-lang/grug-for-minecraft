package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The decision a forwarded settle wait makes between asking the client again and answering from the
 * revision it already answered for (#1086), and the question it has to have asked (#1695). Pure, so
 * every combination is covered here rather than only the one a run's timing happens to reach.
 */
class GrugSettledRevisionTest {
    @Test
    void anAnswerHoldsOnlyForTheRevisionItWasGivenAt() {
        GrugSettledRevision settled = new GrugSettledRevision();
        assertFalse(settled.answeredSettled(1, ""), "nothing has been answered yet");

        settled.record(7, true, "");
        assertTrue(settled.answeredSettled(7, ""));
        assertFalse(settled.answeredSettled(8, ""), "a later revision has not been answered");
        assertFalse(settled.answeredSettled(6, ""), "an earlier revision has been left behind");

        settled.record(8, false, "");
        assertFalse(settled.answeredSettled(8, ""), "an unsettled answer is not a settled one");
        assertFalse(settled.answeredSettled(7, ""), "the revision moved with the answer");
    }

    @Test
    void anAnswerCoversTheQuestionItAnswered() {
        // A settle ask also carries the light box the client has to hold. A mod's game-tick
        // placement can move that box without moving the world revision, so a yes for the old box
        // says nothing about the new one and the client has to be asked (#1695).
        GrugSettledRevision settled = new GrugSettledRevision();
        settled.record(7, true, "box 1");

        assertTrue(settled.answeredSettled(7, "box 1"), "the same question is settled");
        assertFalse(
                settled.answeredSettled(7, "box 2"),
                "a box the client was never asked about is not settled");
        assertFalse(
                settled.answeredSettled(7, ""),
                "nor is the same revision without the light it answered");

        settled.record(7, true, "box 2");
        assertTrue(settled.answeredSettled(7, "box 2"));
        assertFalse(settled.answeredSettled(7, "box 1"), "the old box's answer is gone");
    }

    @Test
    void aNullQuestionReadsAsTheEmptyOne() {
        GrugSettledRevision settled = new GrugSettledRevision();
        settled.record(7, true, null);

        assertTrue(settled.answeredSettled(7, ""));
        assertTrue(settled.answeredSettled(7, null));
        assertFalse(settled.answeredSettled(7, "box"));
    }

    @Test
    void forgettingAnAnswerAsksAgainAtTheSameRevision() {
        GrugSettledRevision settled = new GrugSettledRevision();
        settled.record(3, true, "box");
        assertTrue(settled.answeredSettled(3, "box"));

        settled.forget();
        assertFalse(settled.answeredSettled(3, "box"));
        assertFalse(settled.answeredSettled(3, ""), "the question was forgotten with the answer");
    }

    @Test
    void theQueuedChangesAreFlushedOncePerRevision() {
        GrugSettledRevision settled = new GrugSettledRevision();
        assertTrue(settled.needsFlush(4), "nothing has been flushed yet");

        settled.flushed(4);
        assertFalse(settled.needsFlush(4), "a repeat ask at the same revision sends nothing");
        assertTrue(settled.needsFlush(5), "a later revision's changes have not been sent");

        settled.flushed(5);
        settled.record(5, false, "");
        assertFalse(settled.needsFlush(5), "an unsettled answer still had its changes sent");
        assertFalse(settled.answeredSettled(5, ""));
    }

    @Test
    void forgettingAnAnswerAlsoFlushesTheRevisionAgain() {
        GrugSettledRevision settled = new GrugSettledRevision();
        settled.flushed(3);
        assertFalse(settled.needsFlush(3));

        settled.forget();
        assertTrue(settled.needsFlush(3), "a new run sends the same revision's changes again");
    }
}