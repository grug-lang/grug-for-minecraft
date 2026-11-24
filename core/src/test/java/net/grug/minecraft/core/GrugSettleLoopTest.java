package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the rule that a wait which repeats one answer at one revision reports (#1695). */
class GrugSettleLoopTest {
    private static final String BUSY = "the light engine still has work";
    private static final String FRAMES =
            "the frames (0 of 2 idle frames drawn since the last busy one)";

    @Test
    void answersAtOneRevisionWithOneReasonReportAtTheLimit() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(82142L, BUSY), "ask " + at + " is still under the limit");
        }
        String report = loop.answered(82142L, BUSY);
        assertNotNull(report, "the answer at the limit reports");
        assertTrue(report.contains("82142"), report);
        assertTrue(report.contains(BUSY), report);
        assertTrue(report.contains(String.valueOf(GrugSettleLoop.LIMIT)), report);
        // It keeps reporting after the limit, so the wait cannot fall back into asking forever.
        assertNotNull(loop.answered(82142L, BUSY));
    }

    @Test
    void answersAcrossChangingRevisionsNeverReport() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int revision = 1; revision <= GrugSettleLoop.LIMIT * 3; revision++) {
            assertNull(loop.answered(revision, BUSY), "revision " + revision + " is new");
        }
    }

    @Test
    void answersWhoseReasonChangesNeverReport() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 0; at < GrugSettleLoop.LIMIT * 3; at++) {
            assertNull(loop.answered(7L, at % 2 == 0 ? BUSY : FRAMES), "ask " + at + " changed");
        }
    }

    @Test
    void anUnnamedReasonSaysSo() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(3L, ""));
        }
        String report = loop.answered(3L, "");
        assertNotNull(report);
        assertTrue(report.contains("which this loader does not name"), report);
        assertTrue(report.contains("3"), report);
    }

    @Test
    void aNullReasonReadsAsUnnamed() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(3L, null));
        }
        String report = loop.answered(3L, null);
        assertNotNull(report);
        assertTrue(report.contains("which this loader does not name"), report);
    }

    @Test
    void forgettingTheRunStartsTheCountAgain() {
        GrugSettleLoop loop = new GrugSettleLoop();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            loop.answered(3L, BUSY);
        }
        loop.forget();
        for (int at = 1; at < GrugSettleLoop.LIMIT; at++) {
            assertNull(loop.answered(3L, BUSY), "ask " + at + " of the new run");
        }
    }
}
