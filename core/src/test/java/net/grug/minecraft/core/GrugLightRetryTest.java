package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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

    @Test
    void oneDivergenceSendsTheBoxAgainAndASecondOneIsTheDefect() {
        GrugLightRetry retry = new GrugLightRetry();
        assertEquals(GrugLightRetry.Verdict.SETTLED, retry.answered(GrugControlWire.SETTLE_YES));
        assertEquals(GrugLightRetry.Verdict.UNSETTLED, retry.answered(GrugControlWire.SETTLE_NO));
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                retry.answered(GrugControlWire.SETTLE_LIGHT_DIVERGED));
        // A client that goes back to drawing still holds the field it relit, so the divergence
        // stands until a settled answer.
        assertEquals(GrugLightRetry.Verdict.UNSETTLED, retry.answered(GrugControlWire.SETTLE_NO));
        assertEquals(
                GrugLightRetry.Verdict.PERSISTED,
                retry.answered(GrugControlWire.SETTLE_LIGHT_DIVERGED));
        assertEquals(
                GrugLightRetry.Verdict.PERSISTED,
                retry.answered(GrugControlWire.SETTLE_LIGHT_DIVERGED));
        // A settled answer forgets it, so a later divergence starts over.
        assertEquals(GrugLightRetry.Verdict.SETTLED, retry.answered(GrugControlWire.SETTLE_YES));
        assertEquals(
                GrugLightRetry.Verdict.DIVERGED,
                retry.answered(GrugControlWire.SETTLE_LIGHT_DIVERGED));
    }

    @Test
    void theReportNamesTheFirstDifferingCellAndBothLevels() throws Exception {
        // The server holds open sky over the whole box; the client lost it at one cell.
        int[] box = {1, 64, 0, 3, 65, 1};
        GrugLightSync.BlockLight serverSky = (x, y, z) -> 15;
        GrugLightSync.BlockLight clientSky = (x, y, z) -> x == 2 && y == 64 && z == 1 ? 0 : 15;

        String report = GrugLightRetry.difference(box, DARK, serverSky, probe(DARK, clientSky));

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

        String report = GrugLightRetry.difference(box, serverBlock, DARK, probe(clientBlock, DARK));

        assertTrue(report.contains("first at 1,64,1"), report);
        assertTrue(report.contains("block 7 on this server and 9 on the client"), report);
    }

    @Test
    void aLevelTheClientNeverConfirmsIsReportedAsUnread() throws Exception {
        int[] box = {0, 64, 0, 1, 64, 1};
        // Every sub-box differs, so the cell is never located and no level confirms either.
        GrugLightRetry.Probe never = ask -> GrugControlWire.SETTLE_LIGHT_DIVERGED;

        String report = GrugLightRetry.difference(box, DARK, DARK, never);

        assertTrue(report.contains("the client's level there could not be read"), report);
    }

    @Test
    void aBoxThatNoLongerDiffersSaysSoRatherThanNamingACell() throws Exception {
        int[] box = {0, 64, 0, 1, 64, 1};
        GrugLightRetry.Probe same = ask -> GrugControlWire.SETTLE_YES;

        String report = GrugLightRetry.difference(box, DARK, DARK, same);

        assertTrue(report.contains("no cell of it was found to differ"), report);
        assertTrue(report.contains("0..1, 64..64, 0..1"), report);
    }

    @Test
    void aBoxThatWasNeverSentIsReportedWithoutOne() throws Exception {
        String report =
                GrugLightRetry.difference(
                        new int[0], DARK, DARK, ask -> GrugControlWire.SETTLE_YES);
        assertTrue(report.contains("no box was sent"), report);
    }

    @Test
    void aClientThatStopsDrawingWhileTheCellIsLocatedIsNamed() {
        int[] box = {0, 64, 0, 1, 64, 1};
        GrugControlServer.ControlException failure =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () ->
                                GrugLightRetry.difference(
                                        box, DARK, DARK, ask -> GrugControlWire.SETTLE_NO));
        assertTrue(failure.getMessage().contains("stopped drawing"), failure.getMessage());
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
