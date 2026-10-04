package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.Vec3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

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

        /**
         * The path of the test now running, worked out from the file id the runner creates it with.
         * The runner exposes only the owning mod to the script, so a fake that has to tell tests
         * apart by anything else is guessing.
         */
        String activePath = "";

        /** Set by a test that needs to address its fakes by path. */
        void pathsAre(Map<String, Long> files) {
            for (Map.Entry<String, Long> entry : files.entrySet()) {
                pathOfFileId.put(entry.getValue(), entry.getKey());
            }
        }

        private final Map<Long, String> pathOfFileId = new LinkedHashMap<>();

        @Override
        public long createEntity(long fileId) {
            created++;
            if (throwOnCreate != null) throw throwOnCreate;
            activePath = pathOfFileId.getOrDefault(fileId, "");
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
        Grug.testOrigin = null;
        Grug.printQueue.clear();
        Grug.runtimeErrorQueue.clear();
        Grug.testRuntimeErrors.clear();
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

    /**
     * What one call of a script that asked for an error looks like: the script set the expectation,
     * the host function failed, and the engine handed the message to both the chat and the runner's
     * own record through {@code Grug.onRuntimeError} before it stopped the script, so the export
     * call returns without finishing.
     */
    private static void abortWithHostFunctionError(FakeOps ops, String expected, String message) {
        Grug.testExpectedError = expected;
        reportRuntimeError(message);
        ops.completed = false;
    }

    /** What {@code Grug.onRuntimeError} does with a message the engine reports. */
    private static void reportRuntimeError(String message) {
        Grug.recordTestRuntimeError("Host function error: " + message);
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.add("Host function error: " + message);
        }
    }

    @Test
    void acceptsAMatchingExpectedError() {
        FakeOps ops = new FakeOps();
        ops.onCall = () -> abortWithHostFunctionError(ops, "boom", "boom happened");
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(ops.destroyed == 1);
    }

    @Test
    void passesAnExpectedErrorTheChatQueueNoLongerHolds() {
        // The runner runs on the server thread while the loaders drain the chat queue on the client
        // one, so a client tick landing between the abort and the runner's read took the message
        // with it and the test failed against an empty string. Here the chat queue is already empty
        // by the time the runner looks, which is what that drain leaves behind.
        FakeOps ops = new FakeOps();
        ops.onCall =
                () -> {
                    abortWithHostFunctionError(ops, "boom", "boom happened");
                    Grug.runtimeErrorQueue.clear();
                };
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        // Assert a pass rather than the absence of one particular failure, so a different failure
        // of the same test cannot leave the case green.
        assertTrue(Grug.runtimeErrorQueue.stream().noneMatch(m -> m.contains("FAIL")));
    }

    @Test
    void recordsARuntimeErrorOnlyWhileATestExpectsOne() {
        // Nothing reads the record outside the expect_error path, so an error with no expectation
        // set would be retained for the rest of the session without ever being matched.
        Grug.recordTestRuntimeError("no test is expecting an error");
        assertTrue(Grug.testRuntimeErrors.isEmpty());

        Grug.testExpectedError = "boom";
        Grug.recordTestRuntimeError("boom happened");
        assertEquals(1, Grug.testRuntimeErrors.size());
        assertEquals("boom happened", Grug.testRuntimeErrors.peek());
    }

    @Test
    void clearsAnExpectationWhenTheTestThatAskedForItEnds() {
        // A lingering expectation would keep Grug.onRuntimeError recording errors for a test that
        // is no longer running.
        FakeOps ops = new FakeOps();
        ops.onCall = () -> abortWithHostFunctionError(ops, "boom", "boom happened");
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        assertNull(Grug.testExpectedError);
    }

    @Test
    void rejectsAnExpectedErrorFromAnEarlierTick() {
        // A test can ask for an error on one tick and abort on the next, which is what a record
        // left over from the tick before would answer for it. An unrelated error is raised on the
        // tick the test is still setting up, and the tick after it aborts having raised nothing, so
        // matching that stale message would pass a test on an error it never provoked.
        FakeOps ops = new FakeOps();
        ops.onCall =
                () -> {
                    Grug.testExpectedError = "boom";
                    if (Grug.currentTestTick == 0) {
                        reportRuntimeError("boom happened");
                        Grug.testNotDone = true;
                    } else {
                        ops.completed = false;
                    }
                };
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        assertTrue(
                Grug.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("Expected an error containing 'boom'")));
    }

    @Test
    void rejectsAnExpectedErrorThatDoesNotMatch() {
        FakeOps ops = new FakeOps();
        ops.onCall = () -> abortWithHostFunctionError(ops, "boom", "a different problem");
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

    @Test
    void clearsACapturedOriginBetweenTests() {
        // The origin is captured per test, so the next test has to start from the player's current
        // position rather than inherit the one the previous test first asked for.
        Grug.testOrigin = new Vec3(1.0, 2.0, 3.0);
        FakeOps ops = new FakeOps();
        ops.onCall =
                () -> {
                    assertNull(Grug.testOrigin);
                    Grug.testOrigin = new Vec3(4.0, 5.0, 6.0);
                };
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        assertNull(Grug.testOrigin);
    }

    /**
     * A clock that reports whatever the test tells it to, so a duration is a value the test chose
     * rather than one it had to outwait. That is what makes the order in the test below a fact
     * about the printing rather than about how fast this machine happens to be.
     */
    private static final class StepClock implements LongSupplier {
        private long nanos = 0;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advance(long amount) {
            nanos += amount;
        }
    }

    private static final long MS = 1_000_000L;

    /** Runs {@code body} and returns everything it printed to the console. */
    private static String capture(Runnable body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(saved);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static GrugTestRunner timedRunner(
            FakeOps ops, Map<String, Long> files, LongSupplier c) {
        return new GrugTestRunner(files, List.of(), ops, c);
    }

    /** The rows of the printed table, which are the lines naming a test and its time. */
    private static List<String> tableRows(String printed) {
        return printed.lines()
                .filter(line -> line.contains("ms  mymod/"))
                .collect(Collectors.toList());
    }

    @Test
    void printsEveryTestsTimeSlowestFirst() {
        // The order is the point: someone looking for the test worth fixing reads from the top, so
        // a
        // table in run order would leave them sorting it by hand every time.
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        Map<String, Long> timePerTest = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        files.put("mymod/code/c-Test.grug", 3L);
        timePerTest.put("mymod/code/a-Test.grug", 30 * MS);
        timePerTest.put("mymod/code/b-Test.grug", 10 * MS);
        timePerTest.put("mymod/code/c-Test.grug", 300 * MS);
        ops.pathsAre(files);
        // Each script advances the clock by its own amount, so the durations are the three above
        // rather than whatever three real tests happened to take.
        ops.onCall = () -> clock.advance(timePerTest.get(ops.activePath));

        String printed = capture(() -> runToEnd(timedRunner(ops, files, clock)));

        assertEquals(
                List.of(
                        "[GRUG CI] 300ms  mymod/code/c-Test.grug",
                        "[GRUG CI]  30ms  mymod/code/a-Test.grug",
                        "[GRUG CI]  10ms  mymod/code/b-Test.grug"),
                tableRows(printed),
                printed);
        // The heading totals them, so the sum can be compared against what the run itself took.
        assertTrue(
                printed.contains("[GRUG CI] Test times, slowest first (3 tests, 340ms total):"),
                printed);
    }

    @Test
    void printsNothingWhenNoTestRan() {
        // A heading over no rows is noise, and a run that found no tests is already reported by the
        // line that follows it.
        String printed =
                capture(() -> runToEnd(timedRunner(new FakeOps(), Map.of(), new StepClock())));

        assertFalse(printed.contains("Test times"), printed);
    }

    @Test
    void announcesTheRunAfterTheTimesSoTheyAreNotCutOff() {
        // run-loader.sh stops watching the log at the announcement, so anything printed after it is
        // never read. That is why the times go first.
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        ops.onCall = () -> clock.advance(5 * MS);

        String printed =
                capture(
                        () ->
                                runToEnd(
                                        timedRunner(
                                                ops, Map.of("mymod/code/a-Test.grug", 1L), clock)));

        int times = printed.indexOf("Test times, slowest first");
        int announced = printed.indexOf("ALL 1 TESTS PASSED");
        assertTrue(times >= 0, printed);
        assertTrue(announced >= 0, printed);
        assertTrue(times < announced, printed);
    }

    @Test
    void printsNoTableForAFailedRun() {
        // A failed run prints the failure, not a table. The table only ever claims to describe a
        // passing one, so a failure must not put a partial one out.
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        ops.completed = false;

        String printed =
                capture(
                        () ->
                                runToEnd(
                                        timedRunner(
                                                ops, Map.of("mymod/code/a-Test.grug", 1L), clock)));

        assertFalse(printed.contains("Test times"), printed);
    }
}
