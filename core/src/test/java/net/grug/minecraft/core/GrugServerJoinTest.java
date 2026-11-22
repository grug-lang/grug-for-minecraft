package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/** When a dedicated-server run gives up waiting for a client (#1023). */
class GrugServerJoinTest {
    private static long timeout(Map<String, String> env) {
        return GrugServerJoin.timeoutFrom(env::get);
    }

    @Test
    void theDefaultStaysUnderTheScriptsDeadline() {
        assertEquals(240_000, GrugServerJoin.DEFAULT_TIMEOUT_MILLIS);
        assertTrue(
                GrugServerJoin.DEFAULT_TIMEOUT_MILLIS
                        < GrugServerJoin.RUN_DEADLINE_SECONDS * 1000L);
    }

    @Test
    void outsideACiRunTheDefaultApplies() {
        Map<String, String> env = new HashMap<>();
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "5000");

        assertEquals(GrugServerJoin.DEFAULT_TIMEOUT_MILLIS, timeout(env));
    }

    @Test
    void theScriptsExportedValueIsUsedInACiRun() {
        Map<String, String> env = new HashMap<>();
        env.put("GRUG_CI", "true");
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "5000");

        assertEquals(5000, timeout(env));
    }

    @Test
    void aConnectRunWithoutGrugCiUsesTheExportedValueToo() {
        Map<String, String> env = new HashMap<>();
        env.put("GRUG_CI_CONNECT", "localhost");
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "7000");

        assertEquals(7000, timeout(env));
    }

    @Test
    void anAbsentBlankOrNonPositiveValueFallsBackToTheDefault() {
        Map<String, String> env = new HashMap<>();
        env.put("GRUG_CI", "true");
        assertEquals(GrugServerJoin.DEFAULT_TIMEOUT_MILLIS, timeout(env));
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "  ");
        assertEquals(GrugServerJoin.DEFAULT_TIMEOUT_MILLIS, timeout(env));
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "0");
        assertEquals(GrugServerJoin.DEFAULT_TIMEOUT_MILLIS, timeout(env));
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "-1");
        assertEquals(GrugServerJoin.DEFAULT_TIMEOUT_MILLIS, timeout(env));
    }

    @Test
    void aValueAboveIntRangeIsHonouredNotThrown() {
        Map<String, String> env = new HashMap<>();
        env.put("GRUG_CI", "true");
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "2148000000");

        assertEquals(2_148_000_000L, timeout(env));
    }

    @Test
    void aNonNumericOrOverflowingValueFallsBackToTheDefault() {
        Map<String, String> env = new HashMap<>();
        env.put("GRUG_CI", "true");
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "soon");
        assertEquals(GrugServerJoin.DEFAULT_TIMEOUT_MILLIS, timeout(env));
        env.put("GRUG_CI_JOIN_TIMEOUT_MILLIS", "99999999999999999999");
        assertEquals(GrugServerJoin.DEFAULT_TIMEOUT_MILLIS, timeout(env));
    }

    @Test
    void theRealEnvironmentGivesAPositiveTimeout() {
        assertTrue(GrugServerJoin.timeoutMillis() > 0);
    }

    @Test
    void theWaitTimesOutExactlyAtTheTimeout() {
        assertFalse(GrugServerJoin.timedOut(1000, 1000, 500));
        assertFalse(GrugServerJoin.timedOut(1000, 1499, 500));
        assertTrue(GrugServerJoin.timedOut(1000, 1500, 500));
        assertTrue(GrugServerJoin.timedOut(1000, 9000, 500));
    }

    @Test
    void theReportsNameTheFailureAndItsNumbers() {
        assertArrayEquals(
                new String[] {
                    "[GRUG CI] FAIL world readiness",
                    "[GRUG CI] No player joined the dedicated server within 240 seconds, so the"
                            + " tests were not started."
                },
                GrugServerJoin.timeoutReport(240_000));
        assertArrayEquals(
                new String[] {
                    "[GRUG CI] FAIL the test player left",
                    "[GRUG CI] The player who started the run disconnected while 2 player(s) were"
                            + " still on the dedicated server."
                },
                GrugServerJoin.starterLeftReport(2));
        assertArrayEquals(
                new String[] {
                    "[GRUG CI] FAIL the test player left",
                    "[GRUG CI] Every player disconnected from the dedicated server during the run."
                },
                GrugServerJoin.everyoneLeftReport());
    }
}
