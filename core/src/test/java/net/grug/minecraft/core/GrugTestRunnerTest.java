package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.Grug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Covers {@link GrugTestRunner}'s state machine through its {@code TestEntityOps} seam. */
class GrugTestRunnerTest {

    private static class FakeOps implements GrugTestRunner.TestEntityOps {
        long nextHandle = 100;
        long exportFnId = 1;
        boolean completed = true;
        RuntimeException throwOnCall;
        RuntimeException throwOnCreate;
        Runnable onCall;
        int created;
        int destroyed;

        @Override
        public long createEntity(long fileId) {
            created++;
            if (throwOnCreate != null) throw throwOnCreate;
            return nextHandle++;
        }

        @Override
        public long getExportFnId(String entityType, String fnName) {
            return exportFnId;
        }

        @Override
        public boolean callExportFn(long entityHandle, long exportFnId) {
            if (onCall != null) onCall.run();
            if (throwOnCall != null) throw throwOnCall;
            return completed;
        }

        @Override
        public void destroyEntity(long entityHandle) {
            destroyed++;
        }
    }

    @BeforeEach
    void resetGrugState() {
        Grug.testNotDone = false;
        Grug.testExpectedError = null;
        Grug.currentTestTick = 0;
        Grug.currentTestMod = null;
        Grug.testFidelityOverride = null;
        Grug.printQueue.clear();
        Grug.runtimeErrorQueue.clear();
    }

    private GrugTestRunner runner(GrugTestRunner.TestEntityOps ops, Map<String, Long> files) {
        return new GrugTestRunner(files, List.of(), ops);
    }

    /** The runner only marks itself finished on the tick after the last test passes. */
    private void runToEnd(GrugTestRunner runner) {
        for (int i = 0;
                i < GrugTestRunner.DEFAULT_MAX_TEST_TICKS + 5 && !runner.isFinished();
                i++) {
            runner.tick(null);
        }
    }

    @Test
    void refusesToRunWhenReferenceTreesAreInvalid() {
        GrugTestRunner runner =
                new GrugTestRunner(
                        Map.of("mymod/code/a-Test.grug", 1L), List.of("bad tree"), new FakeOps());
        assertTrue(runner.isFinished());
        assertTrue(Grug.runtimeErrorQueue.stream().anyMatch(m -> m.contains("bad tree")));
    }

    @Test
    void dropsStartupErrorsSoAnExpectingTestCannotMatchOne() {
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.add("Test file misplaced: 'mymod/code/a-Test.grug' ...");
        }
        runner(new FakeOps(), Map.of("mymod/tests/a-Test.grug", 1L));
        synchronized (Grug.runtimeErrorQueue) {
            assertEquals(0, Grug.runtimeErrorQueue.size());
        }
    }

    @Test
    void finishesWhenThereAreNoTests() {
        GrugTestRunner runner = runner(new FakeOps(), Map.of());
        runToEnd(runner);
        assertTrue(runner.isFinished());
    }

    @Test
    void passesACompletedTest() {
        FakeOps ops = new FakeOps();
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(ops.destroyed == 1);
        assertTrue(ops.created == 1);
    }

    @Test
    void ignoresFilesThatAreNotTests() {
        FakeOps ops = new FakeOps();
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Block.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(ops.created == 0);
    }

    @Test
    void acceptsAMatchingExpectedError() {
        FakeOps ops = new FakeOps();
        ops.onCall =
                () -> {
                    Grug.testExpectedError = "boom";
                    synchronized (Grug.runtimeErrorQueue) {
                        Grug.runtimeErrorQueue.add("boom happened");
                    }
                    ops.completed = false;
                };
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(ops.destroyed == 1);
    }

    @Test
    void rejectsAnExpectedErrorThatDoesNotMatch() {
        FakeOps ops = new FakeOps();
        ops.onCall =
                () -> {
                    Grug.testExpectedError = "boom";
                    synchronized (Grug.runtimeErrorQueue) {
                        Grug.runtimeErrorQueue.add("a different problem");
                    }
                    ops.completed = false;
                };
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                Grug.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("Expected an error containing")));
    }

    @Test
    void failsWhenTheScriptAbortsUnexpectedly() {
        FakeOps ops = new FakeOps();
        ops.completed = false;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                Grug.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("reported a runtime error")));
    }

    @Test
    void failsWhenTheEntityCannotBeCreated() {
        FakeOps ops = new FakeOps();
        ops.nextHandle = 0;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                Grug.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("Failed to create an entity")));
    }

    @Test
    void failsWhenTheRunFunctionIsMissing() {
        FakeOps ops = new FakeOps();
        ops.exportFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                Grug.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("missing 'run' export function")));
    }

    @Test
    void failsWhenCallingTheRunFunctionThrows() {
        FakeOps ops = new FakeOps();
        ops.throwOnCall = new IllegalStateException("Broken grug invariant: kaboom");
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(Grug.runtimeErrorQueue.stream().anyMatch(m -> m.equals("kaboom")));
    }

    @Test
    void failsWhenATestNeverStopsAskingForTicks() {
        FakeOps ops = new FakeOps();
        ops.onCall = () -> Grug.testNotDone = true;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                Grug.runtimeErrorQueue.stream()
                        .anyMatch(
                                m ->
                                        m.contains(
                                                "exceeded "
                                                        + GrugTestRunner.DEFAULT_MAX_TEST_TICKS)));
    }

    @Test
    void failsWhenAnExpectedErrorNeverHappens() {
        FakeOps ops = new FakeOps();
        ops.onCall = () -> Grug.testExpectedError = "boom";
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                Grug.runtimeErrorQueue.stream().anyMatch(m -> m.contains("completed without one")));
    }

    @Test
    void keepsRunningWhileATestAsksForMoreTicks() {
        FakeOps ops = new FakeOps();
        final int[] calls = {0};
        ops.onCall =
                () -> {
                    calls[0]++;
                    if (calls[0] < 3) Grug.testNotDone = true;
                };
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runner.tick(null);
        assertFalse(runner.isFinished());
        runner.tick(null);
        assertFalse(runner.isFinished());
        runToEnd(runner);
        assertTrue(runner.isFinished());
    }

    @Test
    void passesEveryTestInOrder() {
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new java.util.LinkedHashMap<>();
        files.put("mymod/code/b-Test.grug", 2L);
        files.put("mymod/code/a-Test.grug", 1L);
        GrugTestRunner runner = runner(ops, files);
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(ops.created == 2);
        assertTrue(ops.destroyed == 2);
    }

    @Test
    void tickIsANoOpAfterTheRunnerFinished() {
        FakeOps ops = new FakeOps();
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        int destroyedAfterFinish = ops.destroyed;

        runner.tick(null);

        assertTrue(runner.isFinished());
        assertTrue(ops.destroyed == destroyedAfterFinish);
    }

    @Test
    void failsWhenCallingTheRunFunctionThrowsAPlainMessage() {
        FakeOps ops = new FakeOps();
        ops.throwOnCall = new IllegalStateException("a plain failure");
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(Grug.runtimeErrorQueue.stream().anyMatch(m -> m.equals("a plain failure")));
    }

    @Test
    void failsWhenCallingTheRunFunctionThrowsWithoutAMessage() {
        FakeOps ops = new FakeOps();
        ops.throwOnCall = new IllegalStateException();
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                Grug.runtimeErrorQueue.stream().anyMatch(m -> m.contains("IllegalStateException")));
    }

    @Test
    void failsWhenCreatingTheEntityThrowsWithoutAMessage() {
        FakeOps ops = new FakeOps();
        ops.throwOnCreate = new IllegalStateException();
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                Grug.runtimeErrorQueue.stream().anyMatch(m -> m.contains("IllegalStateException")));
    }

    @Test
    void tellsTheRuntimeWhichModOwnsTheRunningTest() {
        // The owning mod decides what its tests may assert, so it has to be the mod holding the
        // running test rather than whichever mod a screenshot happens to render.
        FakeOps ops = new FakeOps();
        final List<String> owningMods = new ArrayList<>();
        ops.onCall = () -> owningMods.add(Grug.currentTestMod);

        Map<String, Long> files = new LinkedHashMap<>();
        files.put("secondmod/code/b-Test.grug", 2L);
        files.put("firstmod/code/a-Test.grug", 1L);
        runToEnd(runner(ops, files));

        assertEquals(List.of("firstmod", "secondmod"), owningMods);
    }

    @Test
    void clearsAFidelityOverrideBetweenTests() {
        // An override only ever judges the test that asked for it, so it must not survive into the
        // next one even when that test failed partway through.
        FakeOps ops = new FakeOps();
        ops.onCall =
                () -> {
                    Grug.testFidelityOverride = "exact";
                    ops.completed = false;
                };
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        assertNull(Grug.testFidelityOverride);
    }
}
