package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Covers how CI ends a run once grug has loaded. */
class GrugBootOnlyTest {

    @Test
    void aBootOnlyRunSaysSoAndStopsWithStatusZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> exits = new ArrayList<>();

        GrugBootOnly.finish(name -> "1", new PrintStream(out), exits::add);

        assertEquals("[GRUG CI] GRUG LOADED" + System.lineSeparator(), out.toString());
        assertEquals(List.of(0), exits);
    }

    @Test
    void anyOtherRunGoesOn() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> exits = new ArrayList<>();

        GrugBootOnly.finish(name -> null, new PrintStream(out), exits::add);

        assertEquals("", out.toString());
        assertEquals(List.of(), exits);
    }

    @Test
    void theRealEnvironmentOfATestRunAsksForNothing() {
        // Ending this JVM would end the test run, so this only holds while the variable is unset.
        assertNull(System.getenv("GRUG_CI_BOOT_ONLY"));
        GrugBootOnly.finish();
    }
}
