package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the rule that a wait which never ends reports instead of spending its budget (#1695). */
class GrugSettleLoopTest {
    private static final String BUSY = "the light engine still has work";
    private static final String FRAMES =
            "the frames (0 of 2 idle frames drawn since the last busy one)";

    @Test
    void answersAtOneRevisionReportAtTheRevisionLimit() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(82142L, BUSY, at), "ask " + at + " is still under the limit");
        }
        String report = loop.answered(82142L, BUSY, GrugSettleLoop.LIMIT);
        assertNotNull(report, "the answer at the limit reports");
        assertTrue(report.contains("82142"), report);
        assertTrue(report.contains(BUSY), report);
        assertTrue(report.contains(String.valueOf(GrugSettleLoop.LIMIT)), report);
        // It keeps reporting after the limit, so the wait cannot fall back into asking forever.
        assertNotNull(loop.answered(82142L, BUSY, GrugSettleLoop.LIMIT + 1));
    }

    @Test
    void answersAcrossChangingRevisionsNeverReport() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int revision = 1; revision <= GrugSettleLoop.LIMIT * 3; revision++) {
            assertNull(loop.answered(revision, BUSY, revision), "revision " + revision + " is new");
        }
    }

    @Test
    void aWaitThatPassedEightyFourAsksWithFramesAdvancingStillPasses() {
        // A loaded run's wooden_pipe_corner_items passed on its 84th ask at one revision, so a
        // limit at or near it would fail a wait that was only slow (#1695).
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at <= 84; at++) {
            assertNull(loop.answered(76621L, FRAMES, at), "ask " + at + " of the slow wait");
        }
        for (int at = 85; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(
                    loop.answered(76621L, FRAMES, at), "ask " + at + " is still under the limit");
        }
    }

    @Test
    void aChangingReasonWithAdvancingFramesWaitsForTheRevisionLimit() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(
                    loop.answered(7L, at % 2 == 0 ? BUSY : FRAMES, at),
                    "ask " + at + " changed its reason and drew a frame");
        }
        String report = loop.answered(7L, BUSY, GrugSettleLoop.LIMIT);
        assertNotNull(report);
        assertTrue(report.contains("not settled"), report);
        assertTrue(
                !report.contains("without drawing a frame"),
                "a drawing client is not reported as stalled: " + report);
    }

    @Test
    void aClientThatDrawsNoFrameReportsBeforeTheRevisionLimit() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at <= GrugSettleLoop.STALL_LIMIT; at++) {
            assertNull(loop.answered(76621L, BUSY, 12L), "ask " + at + " is still drawing");
        }
        String report = loop.answered(76621L, BUSY, 12L);
        assertNotNull(report, "a client that drew no frame across the asks reports");
        assertTrue(report.contains("without drawing a frame"), report);
        assertTrue(report.contains("76621"), report);
        assertTrue(report.contains(BUSY), report);
    }

    @Test
    void aLoaderThatCannotCountFramesStillReportsAtTheRevisionLimit() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(3L, BUSY, -1), "ask " + at + " cannot count frames");
        }
        String report = loop.answered(3L, BUSY, -1);
        assertNotNull(report);
        assertTrue(report.contains("3"), report);
    }

    @Test
    void anUnnamedReasonSaysSo() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(3L, "", at));
        }
        String report = loop.answered(3L, "", GrugSettleLoop.LIMIT);
        assertNotNull(report);
        assertTrue(report.contains("which this loader does not name"), report);
        assertTrue(report.contains("3"), report);
    }

    @Test
    void aNullReasonReadsAsUnnamed() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(3L, null, at));
        }
        String report = loop.answered(3L, null, GrugSettleLoop.LIMIT);
        assertNotNull(report);
        assertTrue(report.contains("which this loader does not name"), report);
    }

    @Test
    void forgettingTheRunStartsTheCountAgain() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            loop.answered(3L, BUSY, at);
        }
        loop.forget();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(3L, BUSY, at), "ask " + at + " of the new run");
        }
    }
}
