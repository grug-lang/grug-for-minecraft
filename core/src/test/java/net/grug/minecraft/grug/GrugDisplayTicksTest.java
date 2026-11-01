package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the part of a block's display tick that needs no game: whether a block is asked at all,
 * the report for a declared tick with no export, the context's refusals and the particle record a
 * test reads. The call into the VM and the particles reaching a loader's world are what {@code
 * display_tick-Test.grug} covers on every loader.
 */
class GrugDisplayTicksTest {

    private static final long UNKNOWN_FILE_ID = 987654322L;
    private static final long UNDECLARED_FILE_ID = 987654323L;

    @BeforeEach
    @AfterEach
    void clearState() {
        GrugDisplayTicks.closeContext();
        GrugDisplayTicks.startRecording();
        GrugDisplayTicks.stopRecording();
        GrugDeclarations.blockDataByFileId.remove(UNKNOWN_FILE_ID);
        GrugDeclarations.blockDataByFileId.remove(UNDECLARED_FILE_ID);
        GrugEntities.entityData.clear();
        drain();
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    private static long contextId() {
        return GrugEntities.addEntity(
                GrugEntityType.DisplayTickContext,
                GrugDisplayTicks.openContext(new Object(), () -> 0.25));
    }

    @Test
    void aFileIdThatIsNoBlockIsNotAsked() {
        GrugDisplayTicks.run(UNKNOWN_FILE_ID, new Object(), 0, 0, 0, () -> 0.5);

        assertTrue(drain().isEmpty());
    }

    @Test
    void aBlockThatNeverDeclaredADisplayTickIsNotAsked() {
        GrugDeclarations.blockDataByFileId.put(
                UNDECLARED_FILE_ID, new GrugBlockData("grug:undeclared"));

        GrugDisplayTicks.run(UNDECLARED_FILE_ID, new Object(), 0, 0, 0, () -> 0.5);

        assertTrue(
                drain().isEmpty(), "Nothing enters the VM for a block that did not declare one.");
    }

    @Test
    void aDeclaredTickWithNoExportIsReportedOnce() {
        assertTrue(GrugDisplayTicks.reportMissingExport(false, false, "grug:a_block at 1, 2, 3"));
        assertFalse(GrugDisplayTicks.reportMissingExport(false, false, "grug:a_block at 1, 2, 3"));

        List<String> reports = drain();
        assertEquals(1, reports.size(), "One broken block is one report, not one per sample.");
        assertTrue(reports.get(0).contains("grug:a_block at 1, 2, 3"), reports.get(0));
        assertTrue(reports.get(0).contains("exports no"), reports.get(0));
    }

    @Test
    void aCallThatRanOrAbortedFromItsOwnErrorIsNotReported() {
        assertFalse(GrugDisplayTicks.reportMissingExport(true, false, "grug:ran at 0, 0, 0"));
        assertFalse(GrugDisplayTicks.reportMissingExport(false, true, "grug:errored at 0, 0, 0"));

        assertTrue(drain().isEmpty());
    }

    @Test
    void theOpenContextHandsOutTheGamesGenerator() {
        GrugDisplayTicks.Context context = GrugDisplayTicks.openContext(new Object(), () -> 0.75);

        assertEquals(0.75, context.random());
        assertSame(context, GrugDisplayTicks.context());
        GrugDisplayTicks.closeContext();
        assertNull(GrugDisplayTicks.context());
    }

    @Test
    void onlyTheParticlesAPortAsksForAreKnown() {
        assertNull(GrugDisplayTicks.particleProblem("reddust", 0, 0, 0, 0, 0, 0));
        assertTrue(GrugDisplayTicks.particleProblem("smoke", 0, 0, 0, 0, 0, 0).contains("reddust"));
        String problem = GrugDisplayTicks.particleProblem("heart", 0, 0, 0, 0, 0, 0);
        assertTrue(problem.contains("no particle called \"heart\""), problem);
        assertTrue(problem.contains("The particles are reddust."), problem);
    }

    @Test
    void aPositionOrMotionThatIsNotFiniteIsAProblem() {
        double[][] cases = {
            {Double.NaN, 0, 0, 0, 0, 0},
            {0, Double.POSITIVE_INFINITY, 0, 0, 0, 0},
            {0, 0, Double.NEGATIVE_INFINITY, 0, 0, 0},
            {0, 0, 0, Double.NaN, 0, 0},
            {0, 0, 0, 0, Double.NaN, 0},
            {0, 0, 0, 0, 0, Double.NaN},
        };
        for (double[] c : cases) {
            String problem =
                    GrugDisplayTicks.particleProblem("reddust", c[0], c[1], c[2], c[3], c[4], c[5]);
            assertTrue(problem.contains("must be finite"), problem);
        }
    }

    @Test
    void theRecordHoldsWhatWasRecordedAndNothingOutsideARecording() {
        assertNull(GrugDisplayTicks.recorded(0));
        assertNull(GrugDisplayTicks.recorded(-1));
        assertEquals(0, GrugDisplayTicks.recordedCount());

        GrugDisplayTicks.startRecording();
        GrugDisplayTicks.stopRecording();

        assertEquals(0, GrugDisplayTicks.recordedCount());
        assertNull(GrugDisplayTicks.recorded(0));
    }

    @Test
    void aContextSavedAfterItsCallIsRefusedByEveryMethod() {
        long context = contextId();
        GrugDisplayTicks.closeContext();

        HostFunctions.DisplayTickContext_random(context);
        HostFunctions.DisplayTickContext_add_particle(context, "reddust", 0, 0, 0, 0, 0, 0);

        List<String> reports = drain();
        assertEquals(2, reports.size(), "Every method names its own refusal.");
        assertTrue(reports.get(0).contains("DisplayTickContext.random"), reports.get(0));
        assertTrue(reports.get(1).contains("DisplayTickContext.add_particle"), reports.get(1));
        for (String report : reports) {
            assertTrue(report.contains("saved in a member variable"), report);
        }
    }

    @Test
    void theOpenContextAnswersTheGeneratorAndRefusesNothing() {
        long context = contextId();

        assertEquals(0.25, HostFunctions.DisplayTickContext_random(context));
        assertTrue(drain().isEmpty());
    }

    @Test
    void aRecordedParticleCanBeReadBack() {
        GrugDisplayTicks.startRecording();
        // Recorded the way the context does, minus the loader call that needs a game.
        GrugDisplayTicks.record("reddust", 1, 2, 3, 0, 0.1, 0);
        GrugDisplayTicks.record("reddust", 9, 9, 9, 0, 0, 0);
        GrugDisplayTicks.stopRecording();
        GrugDisplayTicks.record("reddust", 8, 8, 8, 0, 0, 0);

        assertEquals(2, GrugDisplayTicks.recordedCount());
        GrugDisplayTicks.Particle particle = GrugDisplayTicks.recorded(0);
        assertEquals("reddust", particle.name);
        assertEquals(1, particle.x);
        assertEquals(2, particle.y);
        assertEquals(3, particle.z);
        assertEquals(0.1, particle.vy);
        HostFunctions.Test_particle_position(0);
        assertTrue(drain().isEmpty(), "A recorded particle is a position, not a report.");
    }
}
