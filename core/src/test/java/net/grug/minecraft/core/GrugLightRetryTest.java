package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Covers what a loader makes of the light ask's answers, and the report that names the cell a
 * persisting divergence is at (#1695).
 */
class GrugLightRetryTest {
    private static final GrugLightSync.BlockLight DARK = (x, y, z) -> 0;

    /** The client's answer to an ask, which is the comparison the loader makes of its own copy. */
    private static GrugLightRetry.Probe probe(
            GrugLightSync.BlockLight block, GrugLightSync.BlockLight sky) {
        return ask -> {
            GrugLightAsk.Expected expected = GrugLightAsk.decode(ask);
            return GrugLightAsk.holds(expected, block, sky)
                    ? GrugControlWire.SETTLE_YES
                    : GrugControlWire.SETTLE_LIGHT_DIVERGED;
        };
    }

    /** The client's own section fingerprint, as its client-side call answers it. */
    private static GrugLightRetry.SectionRead section(
            GrugLightSync.BlockLight block, GrugLightSync.BlockLight sky) {
        return (x, y, z) -> GrugLightSync.lightFingerprint(x >> 4, y >> 4, z >> 4, block, sky);
    }

    private static GrugLightRetry.Verdict diverged(
            GrugLightRetry retry,
            int[] box,
            GrugLightSync.BlockLight block,
            GrugLightSync.BlockLight sky,
            GrugLightRetry.Probe probe,
            GrugLightRetry.SectionRead section)
            throws GrugControlServer.ControlException {
        return retry.answered(
                GrugControlWire.SETTLE_LIGHT_DIVERGED, box, block, sky, probe, section);
    }

    @Test
    void aFieldThatStopsMovingIsReportedAtItsCell() throws Exception {
        int[] box = {0, 64, 0, 0, 64, 0};
        GrugLightSync.BlockLight serverBlock = (x, y, z) -> 7;
        GrugLightSync.BlockLight serverSky = (x, y, z) -> 3;
        // The client's field does not move across the re-sends, so no send replaces it.
        GrugLightSync.BlockLight clientBlock = (x, y, z) -> 9;
        GrugLightSync.BlockLight clientSky = (x, y, z) -> 0;
        GrugLightRetry.Probe probe = probe(clientBlock, clientSky);
        GrugLightRetry.SectionRead section = section(clientBlock, clientSky);
        GrugLightRetry retry = new GrugLightRetry();

        assertEquals(
                GrugLightRetry.Verdict.SETTLED,
                retry.answered(
                        GrugControlWire.SETTLE_YES, box, serverBlock, serverSky, probe, section));
        assertEquals(
                GrugLightRetry.Verdict.UNSETTLED,
                retry.answered(
                        GrugControlWire.SETTLE_NO, box, serverBlock, serverSky, probe, section));
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(retry, box, serverBlock, serverSky, probe, section),
                "the first divergence sends the box again");
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(retry, box, serverBlock, serverSky, probe, section),
                "a located difference that is still moving gets another send");
        assertEquals(
                GrugLightRetry.Verdict.PERSISTED,
                diverged(retry, box, serverBlock, serverSky, probe, section),
                "the same cell with the same bytes stopped moving, so it reports");
        String report = retry.report();
        assertTrue(report.contains("first at 0,64,0"), report);
        assertTrue(report.contains("sky 3 on this server and 0 on the client"), report);
        assertTrue(report.contains("block 7 on this server and 9 on the client"), report);
        assertEquals(
                GrugLightRetry.Verdict.PERSISTED,
                diverged(retry, box, serverBlock, serverSky, probe, section),
                "it keeps reporting rather than falling back to sending the box again");
    }

    @Test
    void aFieldThatKeepsMovingGetsEveryResendThenReports() throws Exception {
        int[] box = {0, 64, 0, 0, 64, 0};
        GrugLightSync.BlockLight serverBlock = (x, y, z) -> 7;
        int[] client = {9};
        GrugLightSync.BlockLight clientBlock = (x, y, z) -> client[0];
        GrugLightRetry.Probe probe = probe(clientBlock, DARK);
        GrugLightRetry.SectionRead section = section(clientBlock, DARK);
        GrugLightRetry retry = new GrugLightRetry();

        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(retry, box, serverBlock, DARK, probe, section));
        for (int resend = 1; resend < GrugLightRetry.MAX_RESENDS; resend++) {
            client[0]++;
            assertEquals(
                    GrugLightRetry.Verdict.DIVERGED,
                    diverged(retry, box, serverBlock, DARK, probe, section),
                    "re-send "
                            + resend
                            + " of "
                            + GrugLightRetry.MAX_RESENDS
                            + " follows a field that still moves");
        }
        client[0]++;
        assertEquals(
                GrugLightRetry.Verdict.PERSISTED,
                diverged(retry, box, serverBlock, DARK, probe, section),
                "the re-sends are spent, so the last located difference reports");
        assertTrue(
                retry.report()
                        .contains("block 7 on this server and " + client[0] + " on the client"),
                retry.report());
    }

    @Test
    void aSentAnswerSettlesThePollAndSendsTheBoxOnlyWhenItDiverged() {
        GrugLightRetry retry = new GrugLightRetry();
        AtomicInteger sends = new AtomicInteger();
        Runnable resend = sends::incrementAndGet;

        assertTrue(retry.settled(GrugLightRetry.Verdict.SETTLED, resend));
        assertFalse(retry.settled(GrugLightRetry.Verdict.UNSETTLED, resend));
        assertFalse(
                retry.settled(GrugLightRetry.Verdict.DIVERGED, resend),
                "a divergent answer leaves the poll unsettled");
        assertFalse(retry.settled(GrugLightRetry.Verdict.PERSISTED, resend));
        assertEquals(1, sends.get(), "only the divergent answer sends the box again");
    }

    @Test
    void aDivergenceThatOutlastedItsResendsIsThrownAsTheReport() throws Exception {
        GrugLightRetry retry = new GrugLightRetry();

        assertEquals(
                GrugLightRetry.Verdict.SETTLED,
                retry.reportIfPersisted(GrugLightRetry.Verdict.SETTLED));
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                retry.reportIfPersisted(GrugLightRetry.Verdict.DIVERGED));
        GrugControlServer.ControlException thrown =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> retry.reportIfPersisted(GrugLightRetry.Verdict.PERSISTED));
        assertEquals(
                retry.report(),
                thrown.getMessage(),
                "the poll that catches this reports the same cell the retry named");
    }

    @Test
    void forgettingTheDivergenceStartsTheNextTestsWaitOver() throws Exception {
        int[] box = {0, 64, 0, 0, 64, 0};
        GrugLightSync.BlockLight serverBlock = (x, y, z) -> 7;
        GrugLightSync.BlockLight clientBlock = (x, y, z) -> 9;
        GrugLightRetry.Probe probe = probe(clientBlock, DARK);
        GrugLightRetry.SectionRead section = section(clientBlock, DARK);
        GrugLightRetry retry = new GrugLightRetry();
        diverged(retry, box, serverBlock, DARK, probe, section);
        diverged(retry, box, serverBlock, DARK, probe, section);
        diverged(retry, box, serverBlock, DARK, probe, section);
        assertEquals(
                GrugLightRetry.Verdict.PERSISTED,
                diverged(retry, box, serverBlock, DARK, probe, section));

        retry.forget();

        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(retry, box, serverBlock, DARK, probe, section),
                "the next test's first divergence sends the box, it is not reported");
        assertFalse(retry.report().contains("first at"), "the last test's cell is forgotten");
    }

    @Test
    void theReportNamesTheFirstDifferingCellAndBothLevels() throws Exception {
        // The server holds open sky over the whole box; the client lost it at one cell.
        int[] box = {1, 64, 0, 3, 65, 1};
        GrugLightSync.BlockLight serverSky = (x, y, z) -> 15;
        GrugLightSync.BlockLight clientSky = (x, y, z) -> x == 2 && y == 64 && z == 1 ? 0 : 15;

        String report =
                GrugLightRetry.firstDifference(box, DARK, serverSky, probe(DARK, clientSky), null)
                        .describe();

        assertTrue(report.contains("first at 2,64,1"), report);
        assertTrue(report.contains("sky 15 on this server and 0 on the client"), report);
        assertTrue(report.contains("block 0 on this server and 0 on the client"), report);
    }

    @Test
    void theReportReachesTheCellTheFingerprintsOrderReachesFirst() throws Exception {
        int[] box = {1, 64, 0, 4, 65, 1};
        GrugLightSync.BlockLight serverBlock = (x, y, z) -> 7;
        // Two cells differ: (3,65,0) comes after (1,64,1) in x then y then z order.
        GrugLightSync.BlockLight clientBlock =
                (x, y, z) -> (x == 1 && y == 64 && z == 1) || (x == 3 && y == 65 && z == 0) ? 9 : 7;

        String report =
                GrugLightRetry.firstDifference(
                                box, serverBlock, DARK, probe(clientBlock, DARK), null)
                        .describe();

        assertTrue(report.contains("first at 1,64,1"), report);
        assertTrue(report.contains("block 7 on this server and 9 on the client"), report);
    }

    @Test
    void theLevelIsReadFromOneSectionFingerprintRatherThanAWalOfAsks() throws Exception {
        int[] box = {0, 64, 0, 0, 64, 0};
        GrugLightSync.BlockLight serverBlock = (x, y, z) -> 7;
        GrugLightSync.BlockLight serverSky = (x, y, z) -> 3;
        GrugLightSync.BlockLight clientBlock = (x, y, z) -> x == 0 && y == 64 && z == 0 ? 9 : 7;
        GrugLightSync.BlockLight clientSky = (x, y, z) -> x == 0 && y == 64 && z == 0 ? 0 : 3;
        AtomicInteger asks = new AtomicInteger();
        GrugLightRetry.Probe probe = probe(clientBlock, clientSky);
        GrugLightRetry.Probe counted =
                ask -> {
                    asks.incrementAndGet();
                    return probe.probe(ask);
                };

        String report =
                GrugLightRetry.firstDifference(
                                box,
                                serverBlock,
                                serverSky,
                                counted,
                                section(clientBlock, clientSky))
                        .describe();

        assertTrue(report.contains("block 7 on this server and 9 on the client"), report);
        assertEquals(
                1,
                asks.get(),
                "the cell was located by one ask and the level came from the section fingerprint");
    }

    @Test
    void aSectionTheClientCannotReadFallsBackToBoundedCandidates() throws Exception {
        int[] box = {0, 64, 0, 0, 64, 0};
        AtomicInteger asks = new AtomicInteger();
        GrugLightRetry.Probe always =
                ask -> {
                    asks.incrementAndGet();
                    return GrugControlWire.SETTLE_LIGHT_DIVERGED;
                };

        String report =
                GrugLightRetry.firstDifference(
                                box, DARK, DARK, always, (x, y, z) -> GrugClientLight.CANNOT_VERIFY)
                        .describe();

        assertTrue(report.contains("the client's level there could not be read"), report);
        assertTrue(
                asks.get() <= 1 + GrugLightRetry.CANDIDATE_LIMIT,
                "the report asked " + asks.get() + " times, over the candidate limit plus one");
    }

    @Test
    void aBoxThatNoLongerDiffersHasNoDifference() throws Exception {
        int[] box = {0, 64, 0, 1, 64, 1};

        assertNull(
                GrugLightRetry.firstDifference(
                        box, DARK, DARK, ask -> GrugControlWire.SETTLE_YES, null));
    }

    @Test
    void aBoxThatWasNeverSentHasNoDifference() throws Exception {
        assertNull(
                GrugLightRetry.firstDifference(
                        new int[0], DARK, DARK, ask -> GrugControlWire.SETTLE_YES, null));
        assertTrue(new GrugLightRetry().report().contains("could not be located"));
    }

    @Test
    void aDifferenceIsTheSameOnlyAtTheSameCellAndTheSameBytes() {
        // A field that stopped moving is the same cell with the same bytes, so a difference in any
        // one of the six decides it; the report's early answer depends on every comparison (#1695).
        GrugLightRetry.Difference base = new GrugLightRetry.Difference(1, 2, 3, 4, 5, 6);

        assertTrue(base.sameAs(new GrugLightRetry.Difference(1, 2, 3, 4, 5, 6)));
        assertFalse(
                base.sameAs(new GrugLightRetry.Difference(9, 2, 3, 4, 5, 6)), "another x differs");
        assertFalse(
                base.sameAs(new GrugLightRetry.Difference(1, 9, 3, 4, 5, 6)), "another y differs");
        assertFalse(
                base.sameAs(new GrugLightRetry.Difference(1, 2, 9, 4, 5, 6)), "another z differs");
        assertFalse(
                base.sameAs(new GrugLightRetry.Difference(1, 2, 3, 9, 5, 6)),
                "another block level differs");
        assertFalse(
                base.sameAs(new GrugLightRetry.Difference(1, 2, 3, 4, 9, 6)),
                "another sky level differs");
        assertFalse(
                base.sameAs(new GrugLightRetry.Difference(1, 2, 3, 4, 5, 9)),
                "another client level differs");
    }

    @Test
    void aBoxThatNoLongerDiffersWhenItIsAskedAgainHasNothingToReport() throws Exception {
        // A client can move back into agreement between two divergent answers, so the ask that
        // follows a located cell locates none. The wait asks again rather than reporting a
        // difference that is gone.
        int[] box = {0, 64, 0, 0, 64, 0};
        GrugLightSync.BlockLight serverBlock = (x, y, z) -> 7;
        GrugLightSync.BlockLight clientBlock = (x, y, z) -> 9;
        GrugLightRetry retry = new GrugLightRetry();

        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(
                        retry,
                        box,
                        serverBlock,
                        DARK,
                        probe(clientBlock, DARK),
                        section(clientBlock, DARK)),
                "the first divergence only sends the box again");
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(
                        retry,
                        box,
                        serverBlock,
                        DARK,
                        probe(clientBlock, DARK),
                        section(clientBlock, DARK)),
                "the second locates the cell and sends the box again");
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(retry, box, serverBlock, DARK, ask -> GrugControlWire.SETTLE_YES, null),
                "a box that agrees again has no cell to report");
        assertTrue(retry.report().contains("could not be located"), retry.report());
    }

    @Test
    void aClientThatStaysBusyWhileTheCellIsLocatedIsNamed() {
        // The client re-evaluates its settled state on every ask, so one busy answer during a probe
        // is not a verdict on the light: the probe asks again rather than aborting the diagnosis
        // (#1695).
        int[] box = {0, 64, 0, 1, 64, 1};
        AtomicInteger asks = new AtomicInteger();
        GrugControlServer.ControlException failure =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () ->
                                GrugLightRetry.firstDifference(
                                        box,
                                        DARK,
                                        DARK,
                                        ask -> {
                                            asks.incrementAndGet();
                                            return GrugControlWire.SETTLE_NO;
                                        },
                                        null));
        assertTrue(failure.getMessage().contains("still settling"), failure.getMessage());
        assertEquals(
                GrugLightRetry.BUSY_RETRIES + 1,
                asks.get(),
                "the probe gives up after the retries rather than on the first busy answer");
    }

    @Test
    void oneBusyAnswerDuringAProbeDoesNotAbortTheDiagnosis() throws Exception {
        int[] box = {0, 64, 0, 0, 64, 0};
        GrugLightSync.BlockLight serverBlock = (x, y, z) -> 7;
        GrugLightSync.BlockLight clientBlock = (x, y, z) -> 9;
        GrugLightRetry.Probe honest = probe(clientBlock, DARK);
        AtomicInteger asked = new AtomicInteger();
        GrugLightRetry.Probe busyOnce =
                ask -> asked.getAndIncrement() == 0 ? GrugControlWire.SETTLE_NO : honest.probe(ask);

        String report =
                GrugLightRetry.firstDifference(box, serverBlock, DARK, busyOnce, null).describe();

        assertTrue(report.contains("first at 0,64,0"), report);
        assertTrue(report.contains("block 7 on this server and 9 on the client"), report);
        assertTrue(asked.get() > 1, "the busy answer was asked again");
    }

    @Test
    void anUnreadClientByteDoesNotEndTheSendsEarly() throws Exception {
        // Two unread bytes compare equal in Difference.sameAs, so a repeat of them is not evidence
        // that the field stopped moving. The wait keeps its three re-sends and reports at the cap
        // (#1695).
        int[] box = {0, 64, 0, 0, 64, 0};
        GrugLightSync.BlockLight serverBlock = (x, y, z) -> 7;
        GrugLightRetry.Probe never = ask -> GrugControlWire.SETTLE_LIGHT_DIVERGED;
        GrugLightRetry retry = new GrugLightRetry();

        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(
                        retry,
                        box,
                        serverBlock,
                        DARK,
                        never,
                        (x, y, z) -> GrugClientLight.CANNOT_VERIFY));
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(
                        retry,
                        box,
                        serverBlock,
                        DARK,
                        never,
                        (x, y, z) -> GrugClientLight.CANNOT_VERIFY));
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                diverged(
                        retry,
                        box,
                        serverBlock,
                        DARK,
                        never,
                        (x, y, z) -> GrugClientLight.CANNOT_VERIFY),
                "an unread byte that repeats is not a field that stopped moving");
        assertEquals(
                GrugLightRetry.Verdict.PERSISTED,
                diverged(
                        retry,
                        box,
                        serverBlock,
                        DARK,
                        never,
                        (x, y, z) -> GrugClientLight.CANNOT_VERIFY),
                "the re-sends are spent, so the unread cell reports");
        assertTrue(retry.report().contains("could not be read"), retry.report());
    }

    @Test
    void theCandidatesCoverEveryLevelByteWithTheServersOwnFirst() {
        int[] order = GrugLightRetry.candidates(0xF3);
        assertEquals(256, order.length);
        assertEquals(0xF3, order[0]);
        // Every byte appears exactly once, so the search can never miss the client's level.
        boolean[] seen = new boolean[256];
        for (int level : order) {
            assertTrue(level >= 0 && level < 256);
            assertFalse(seen[level], "level " + level + " appears twice");
            seen[level] = true;
        }
        for (boolean present : seen) {
            assertTrue(present, "every level byte is tried");
        }
    }

    @Test
    void aSingleCellBoxIsTheCellItself() throws Exception {
        int[] box = {5, 70, 5, 5, 70, 5};

        assertNull(GrugLightRetry.firstDifferingCell(box, part -> false));
        int[] cell = GrugLightRetry.firstDifferingCell(box, part -> true);
        assertNotNull(cell);
        assertEquals(5, cell[0]);
        assertEquals(70, cell[1]);
        assertEquals(5, cell[2]);
    }
}
