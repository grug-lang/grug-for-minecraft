package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Covers {@link GrugTestRunner}'s state machine through its {@code TestEntityOps} seam. */
class GrugTestRunnerTest {

    private static class FakeOps implements GrugTestRunner.TestEntityOps {
        long nextHandle = 100;
        long exportFnId = 1;

        /** What the runner is told for the init export; invalid for a test without one. */
        long initFnId = 1;

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
            return fnName.equals("init") ? initFnId : exportFnId;
        }

        @Override
        public boolean callExportFn(long entityHandle, long exportFnId) {
            if (onCall != null) onCall.run();
            if (throwOnCall != null) throw throwOnCall;
            return completed;
        }

        /** What the test's init does when the runner calls it; null for a test without one. */
        Runnable onInit;

        @Override
        public boolean callInitFn(long entityHandle, long exportFnId) {
            if (onInit != null) onInit.run();
            return true;
        }

        @Override
        public void destroyEntity(long entityHandle) {
            destroyed++;
        }
    }

    @BeforeEach
    void resetGrugState() {
        GrugTestState.testNotDone = false;
        GrugTestState.testExpectedError = null;
        GrugTestState.currentTestTick = 0;
        GrugTestState.currentTestMod = null;
        GrugTestState.testOrigin = null;
        GrugTestState.maxTestTicks = GrugTestRunner.DEFAULT_MAX_TEST_TICKS;
        GrugTestState.runtimeErrorCount = 0;
        GrugErrors.printQueue.clear();
        GrugErrors.runtimeErrorQueue.clear();
        GrugTestState.testRuntimeErrors.clear();
    }

    /** How many times the runner asked the installed adapter to restore the player. */
    private final List<String> playerRestores = new ArrayList<>();

    /** A directory the tests may put a test order record in, cleared between the tests. */
    @TempDir Path tempDir;

    private Object savedAdapter;

    /**
     * Installs a fake adapter, as the other tests that reach {@code GrugCore.getAdapter()} do,
     * because the runner restores the player through it whenever a test ends. The snap is recorded
     * too, so a test can say it came before the room. Every other method returns null, which is all
     * those other tests need.
     */
    @BeforeEach
    void installAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("restorePlayerAfterTest")) {
                                playerRestores.add("restore");
                            }
                            return null;
                        }));
    }

    @AfterEach
    void restoreAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
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
        assertTrue(GrugErrors.runtimeErrorQueue.stream().anyMatch(m -> m.contains("bad tree")));
    }

    @Test
    void dropsStartupErrorsSoAnExpectingTestCannotMatchOne() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.add("Test file misplaced: 'mymod/code/a-Test.grug' ...");
        }
        runner(new FakeOps(), Map.of("mymod/tests/a-Test.grug", 1L));
        synchronized (GrugErrors.runtimeErrorQueue) {
            assertEquals(0, GrugErrors.runtimeErrorQueue.size());
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
    void asksForNoFrameWhileATestRunsUntilItDoes() {
        // The runner turns drawing off once a test's room is built and back on when the test ends,
        // so a test that never captures draws nothing. See GrugFrameRequest and #770.
        FakeOps ops = new FakeOps();
        // Keeps the test alive through the tick that starts it, so the flag can be read with the
        // test still running.
        ops.onCall = () -> GrugTestState.testNotDone = true;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));

        GrugFrameRequest.endTest();
        runner.tick(null);
        assertFalse(GrugFrameRequest.wanted());

        ops.onCall = null;
        runToEnd(runner);
        assertTrue(GrugFrameRequest.wanted());
    }

    @Test
    void parsesTheRepeatCount() {
        assertEquals(1, GrugTestRunner.parseRepeats(null));
        assertEquals(1, GrugTestRunner.parseRepeats(""));
        assertEquals(4, GrugTestRunner.parseRepeats("4"));
        assertThrows(IllegalStateException.class, () -> GrugTestRunner.parseRepeats("0"));
        assertThrows(IllegalStateException.class, () -> GrugTestRunner.parseRepeats("two"));
    }

    @Test
    void repeatsTheSuiteAndAnnouncesOnceAtTheEnd() {
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        ops.pathsAre(files);
        GrugTestRunner runner =
                new GrugTestRunner(
                        files,
                        List.of(),
                        ops,
                        System::nanoTime,
                        new SweepLog(new ArrayList<>()),
                        3);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            runToEnd(runner);
        } finally {
            System.setOut(old);
        }

        assertTrue(runner.isFinished());
        // Two tests, three times, each created and destroyed once.
        assertEquals(6, ops.created);
        assertEquals(6, ops.destroyed);
        String log = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, log.split("ALL 2 TESTS PASSED", -1).length - 1);
        assertEquals(2, log.split("complete, starting the next", -1).length - 1);
    }

    @Test
    void countsATestAsStartedOnlyAfterItsRoomIsBuilt() {
        // A loader stamps a runner tick with this count when it queues the tick, and drops it if a
        // later start has overtaken the stamp, so the count has to move while the room is built,
        // which is the long block the client keeps queueing ticks through, and never on a tick of a
        // test that is already running. See #717.
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        ops.pathsAre(files);
        int[] calls = {0};
        // The first test keeps asking for ticks, so there is a tick that starts nothing.
        ops.onCall = () -> GrugTestState.testNotDone = ++calls[0] < 3;

        GrugTestRunner[] self = new GrugTestRunner[1];
        List<Long> startedWhenBuilt = new ArrayList<>();
        GrugTestRunner runner =
                new GrugTestRunner(
                        files,
                        List.of(),
                        ops,
                        System::nanoTime,
                        new GrugTestRunner.TestBoxBuilder() {
                            @Override
                            public void build(int radius) {
                                startedWhenBuilt.add(self[0].startedTests());
                            }

                            @Override
                            public void sweepItems() {}
                        },
                        1);
        self[0] = runner;

        assertEquals(0, runner.startedTests(), "No test has started before the first tick.");
        runner.tick(null);
        assertEquals(1, runner.startedTests(), "The first tick starts the first test.");
        assertEquals(
                List.of(0L),
                startedWhenBuilt,
                "The count moves after the build: a tick the client queues during the build must"
                        + " not carry the count of the test the build is for.");
        runner.tick(null);
        assertEquals(1, runner.startedTests(), "A tick of a running test starts nothing.");
        runner.tick(null);
        // The third call finishes the first test, and the tick after it starts the second.
        runner.tick(null);
        assertEquals(2, runner.startedTests());
        assertEquals(List.of(0L, 1L), startedWhenBuilt);
    }

    @Test
    void aQueuedTickIsStaleOnlyOnceALaterTestHasStarted() {
        // The loader reads the count when it queues the task and asks this when the task runs, so a
        // count that did not move is the same test and the tick belongs to it (#717).
        assertFalse(
                GrugTestRunner.queuedTickIsStale(0L, 0L), "No start in between: the tick runs.");
        assertFalse(
                GrugTestRunner.queuedTickIsStale(7L, 7L),
                "The same count, whatever it is, is the same test.");
        assertTrue(
                GrugTestRunner.queuedTickIsStale(0L, 1L),
                "The next test started while the tick was queued: it is dropped.");
        assertTrue(
                GrugTestRunner.queuedTickIsStale(7L, 9L),
                "Any later start makes the tick stale, not only the next one.");
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
     * own record through {@code GrugErrors.onRuntimeError} before it stopped the script, so the
     * export call returns without finishing.
     */
    private static void abortWithHostFunctionError(FakeOps ops, String expected, String message) {
        GrugTestState.testExpectedError = expected;
        reportRuntimeError(message);
        ops.completed = false;
    }

    /** What {@code GrugErrors.onRuntimeError} does with a message the engine reports. */
    private static void reportRuntimeError(String message) {
        GrugErrors.recordTestRuntimeError("Host function error: " + message);
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.add("Host function error: " + message);
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
                    GrugErrors.runtimeErrorQueue.clear();
                };
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        // Assert a pass rather than the absence of one particular failure, so a different failure
        // of the same test cannot leave the case green.
        assertTrue(GrugErrors.runtimeErrorQueue.stream().noneMatch(m -> m.contains("FAIL")));
    }

    @Test
    void recordsARuntimeErrorOnlyWhileATestExpectsOne() {
        // Nothing reads the record outside the expect_error path, so an error with no expectation
        // set would be retained for the rest of the session without ever being matched.
        GrugErrors.recordTestRuntimeError("no test is expecting an error");
        assertTrue(GrugTestState.testRuntimeErrors.isEmpty());

        GrugTestState.testExpectedError = "boom";
        GrugErrors.recordTestRuntimeError("boom happened");
        assertEquals(1, GrugTestState.testRuntimeErrors.size());
        assertEquals("boom happened", GrugTestState.testRuntimeErrors.peek());
    }

    @Test
    void clearsAnExpectationWhenTheTestThatAskedForItEnds() {
        // A lingering expectation would keep GrugErrors.onRuntimeError recording errors for a test
        // that is no longer running.
        FakeOps ops = new FakeOps();
        ops.onCall = () -> abortWithHostFunctionError(ops, "boom", "boom happened");
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        assertNull(GrugTestState.testExpectedError);
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
                    GrugTestState.testExpectedError = "boom";
                    if (GrugTestState.currentTestTick == 0) {
                        reportRuntimeError("boom happened");
                        GrugTestState.testNotDone = true;
                    } else {
                        ops.completed = false;
                    }
                };
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
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
                GrugErrors.runtimeErrorQueue.stream()
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
                GrugErrors.runtimeErrorQueue.stream()
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
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("Failed to create an entity")));
    }

    @Test
    void failsWhenTheTickFunctionIsMissing() {
        FakeOps ops = new FakeOps();
        ops.exportFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("missing 'tick' export function")));
    }

    @Test
    void failsWhenCallingTheRunFunctionThrows() {
        FakeOps ops = new FakeOps();
        ops.throwOnCall = new IllegalStateException("Broken grug invariant: kaboom");
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(GrugErrors.runtimeErrorQueue.stream().anyMatch(m -> m.equals("kaboom")));
    }

    @Test
    void failsWhenATestNeverStopsAskingForTicks() {
        FakeOps ops = new FakeOps();
        ops.onCall = () -> GrugTestState.testNotDone = true;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(
                                m ->
                                        m.contains(
                                                "exceeded "
                                                        + GrugTestRunner.DEFAULT_MAX_TEST_TICKS)));
    }

    @Test
    void aTestCanRaiseItsBudgetFromItsInit() {
        FakeOps ops = new FakeOps();
        int raised = GrugTestRunner.DEFAULT_MAX_TEST_TICKS * 2;
        ops.onInit = () -> GrugTestState.maxTestTicks = raised;
        int[] calls = {0};
        ops.onCall =
                () -> {
                    calls[0]++;
                    GrugTestState.testNotDone = calls[0] < raised - 1;
                };
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        for (int i = 0; i < raised + 5 && !runner.isFinished(); i++) {
            runner.tick(null);
        }
        assertTrue(runner.isFinished());
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream().noneMatch(m -> m.contains("FAIL")),
                "A raised budget lets the test run past the default.");
        assertEquals(raised - 1, calls[0]);
    }

    @Test
    void aRaisedBudgetStillEndsATestThatNeverStops() {
        FakeOps ops = new FakeOps();
        int raised = GrugTestRunner.DEFAULT_MAX_TEST_TICKS * 2;
        ops.onInit = () -> GrugTestState.maxTestTicks = raised;
        ops.onCall = () -> GrugTestState.testNotDone = true;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        for (int i = 0; i < raised + 5 && !runner.isFinished(); i++) {
            runner.tick(null);
        }
        assertTrue(runner.isFinished());
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("exceeded " + raised + " ticks")),
                "The message names the budget the test had.");
    }

    @Test
    void theBudgetGoesBackToTheDefaultForTheNextTest() {
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        ops.pathsAre(files);
        int raised = GrugTestRunner.DEFAULT_MAX_TEST_TICKS * 2;
        List<Integer> budgets = new ArrayList<>();
        ops.onInit =
                () -> {
                    budgets.add(GrugTestState.maxTestTicks);
                    if (ops.activePath.startsWith("mymod/code/a")) {
                        GrugTestState.maxTestTicks = raised;
                    }
                };
        GrugTestRunner runner = runner(ops, files);
        runToEnd(runner);
        assertEquals(
                List.of(
                        GrugTestRunner.DEFAULT_MAX_TEST_TICKS,
                        GrugTestRunner.DEFAULT_MAX_TEST_TICKS),
                budgets,
                "Each test starts from the default, whatever the one before it raised.");
    }

    @Test
    void aTestWithoutAnInitIsNeverAskedForOne() {
        FakeOps ops = new FakeOps();
        ops.initFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
        int[] inits = {0};
        ops.onInit = () -> inits[0]++;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertEquals(0, inits[0]);
        assertEquals(
                GrugTestRunner.DEFAULT_MAX_TEST_TICKS,
                GrugTestState.maxTestTicks,
                "Without an init the test keeps the default budget.");
        assertTrue(GrugErrors.runtimeErrorQueue.stream().noneMatch(m -> m.contains("FAIL")));
    }

    @Test
    void failsATestWhoseInitRaisedAnError() {
        FakeOps ops = new FakeOps();
        ops.onInit = () -> GrugTestState.runtimeErrorCount++;
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("init reported a runtime error")));
    }

    @Test
    void failsWhenAnExpectedErrorNeverHappens() {
        FakeOps ops = new FakeOps();
        ops.onCall = () -> GrugTestState.testExpectedError = "boom";
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("completed without one")));
    }

    @Test
    void keepsRunningWhileATestAsksForMoreTicks() {
        FakeOps ops = new FakeOps();
        final int[] calls = {0};
        ops.onCall =
                () -> {
                    calls[0]++;
                    if (calls[0] < 3) GrugTestState.testNotDone = true;
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

    /** Notes what the runner tells the coverage recorder, in order. */
    private static class CoverageLog implements GrugTestRunner.CoverageRecorder {
        final List<String> events = new ArrayList<>();

        @Override
        public void testStarting(String testPath) {
            events.add("starting " + testPath);
        }

        @Override
        public void runPassed() {
            events.add("passed");
        }
    }

    private GrugTestRunner runnerWithCoverage(
            FakeOps ops, Map<String, Long> files, int repeats, CoverageLog coverage) {
        ops.pathsAre(files);
        return new GrugTestRunner(
                files,
                List.of(),
                ops,
                System::nanoTime,
                new SweepLog(new ArrayList<>()),
                repeats,
                coverage);
    }

    @Test
    void tellsTheCoverageRecorderWhichTestEachSegmentIsForInRunOrder() {
        // Put in out of order, so the order the recorder hears is the runner's own sort by path.
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("jei/code/a-Test.grug", 1L);
        files.put("coverage/tests/b-Test.grug", 2L);
        files.put("buildcraft/code/a-Test.grug", 3L);
        files.put("coverage/tests/a-Test.grug", 4L);
        CoverageLog coverage = new CoverageLog();
        GrugTestRunner runner = runnerWithCoverage(new FakeOps(), files, 1, coverage);
        runToEnd(runner);

        assertTrue(runner.isFinished());
        assertEquals(
                List.of(
                        "starting buildcraft/code/a-Test.grug",
                        "starting coverage/tests/a-Test.grug",
                        "starting coverage/tests/b-Test.grug",
                        "starting jei/code/a-Test.grug",
                        "passed"),
                coverage.events);
    }

    @Test
    void tellsTheCoverageRecorderAgainForEveryRepeat() {
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("buildcraft/code/a-Test.grug", 1L);
        files.put("coverage/tests/a-Test.grug", 2L);
        CoverageLog coverage = new CoverageLog();
        runToEnd(runnerWithCoverage(new FakeOps(), files, 2, coverage));

        assertEquals(
                List.of(
                        "starting buildcraft/code/a-Test.grug",
                        "starting coverage/tests/a-Test.grug",
                        "starting buildcraft/code/a-Test.grug",
                        "starting coverage/tests/a-Test.grug",
                        "passed"),
                coverage.events);
    }

    @Test
    void doesNotTellTheCoverageRecorderThatAFailedRunPassed() {
        FakeOps ops = new FakeOps();
        ops.completed = false;
        CoverageLog coverage = new CoverageLog();
        runToEnd(runnerWithCoverage(ops, Map.of("coverage/tests/a-Test.grug", 1L), 1, coverage));

        assertEquals(List.of("starting coverage/tests/a-Test.grug"), coverage.events);
    }

    /**
     * Drains the fatal a test provoked: {@code Grug.fatal} remembers the first error per thread and
     * rethrows it at the next native call boundary, which would fail an unrelated test.
     */
    @AfterEach
    void drainPendingFatal() {
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive the test.
        }
    }

    @Test
    void parsesTheModSelection() {
        // Unset, empty and blank all select every mod, which is the empty set.
        assertEquals(Set.of(), GrugTestRunner.parseMods(null));
        assertEquals(Set.of(), GrugTestRunner.parseMods(""));
        assertEquals(Set.of(), GrugTestRunner.parseMods("  \t "));
        assertEquals(List.of("coverage"), List.copyOf(GrugTestRunner.parseMods("coverage")));
        // Order as given, whitespace trimmed, duplicates dropped.
        assertEquals(
                List.of("buildcraft", "coverage"),
                List.copyOf(GrugTestRunner.parseMods(" buildcraft , coverage,buildcraft")));
    }

    @Test
    void refusesAModSelectionWithAnEmptyEntry() {
        for (String value : new String[] {",", "coverage,", ",coverage", "a,,b", "a, ,b"}) {
            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class, () -> GrugTestRunner.parseMods(value));
            assertTrue(e.getMessage().contains("GRUG_CI_MODS has an empty entry"), e.getMessage());
        }
    }

    private static Map<String, Long> threeMods() {
        // Not in path order, so the order the tests run in is the runner's own sort. The
        // coverage mod has a non-test file of its own, as the real one does, so a loop over the
        // files meets a path in the counted mod that is not a test.
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("coverage/tests/b-Test.grug", 1L);
        files.put("buildcraft/code/a-Test.grug", 2L);
        files.put("coverage/tests/a-Test.grug", 3L);
        files.put("jei/code/a-Test.grug", 4L);
        files.put("examplemod/code/foo-Block.grug", 5L);
        files.put("buildcraft/code/b-Test.grug", 6L);
        files.put("coverage/code/foo-Block.grug", 7L);
        return files;
    }

    private GrugTestRunner selectingRunner(
            FakeOps ops, Map<String, Long> files, int repeats, Set<String> mods, CoverageLog log) {
        ops.pathsAre(files);
        return new GrugTestRunner(
                files,
                List.of(),
                ops,
                System::nanoTime,
                new SweepLog(new ArrayList<>()),
                repeats,
                log,
                mods);
    }

    private GrugTestRunner carryingRunner(
            FakeOps ops,
            Map<String, Long> files,
            int repeats,
            Set<String> mods,
            Set<String> carryOver,
            CoverageLog log) {
        ops.pathsAre(files);
        return new GrugTestRunner(
                files,
                List.of(),
                ops,
                System::nanoTime,
                new SweepLog(new ArrayList<>()),
                repeats,
                log,
                mods,
                GrugTestRunner.MAX_GAME_SPEED,
                carryOver);
    }

    private static List<String> executedTests(String printed) {
        return printed.lines()
                .filter(line -> line.startsWith("[GRUG CI] Executing test: "))
                .map(line -> line.substring("[GRUG CI] Executing test: ".length()))
                .collect(Collectors.toList());
    }

    @Test
    void runsEveryModsTestsWhenNoModIsSelected() {
        String printed =
                capture(
                        () ->
                                runToEnd(
                                        selectingRunner(
                                                new FakeOps(),
                                                threeMods(),
                                                1,
                                                Set.of(),
                                                new CoverageLog())));

        assertEquals(
                List.of(
                        "buildcraft/code/a-Test.grug",
                        "buildcraft/code/b-Test.grug",
                        "coverage/tests/a-Test.grug",
                        "coverage/tests/b-Test.grug",
                        "jei/code/a-Test.grug"),
                executedTests(printed));
        assertTrue(printed.contains("Running 5 tests..."), printed);
        assertTrue(printed.contains("ALL 5 TESTS PASSED"), printed);
        assertFalse(printed.contains("Only the tests of these mods run"), printed);
    }

    @Test
    void runsOnlyTheSelectedModsTestsInTheSamePathOrderAndCountsOnlyThem() {
        FakeOps ops = new FakeOps();
        // The selection lists jei first, and the run still goes by path.
        Set<String> mods = GrugTestRunner.parseMods("jei,buildcraft");
        String printed =
                capture(
                        () ->
                                runToEnd(
                                        selectingRunner(
                                                ops, threeMods(), 1, mods, new CoverageLog())));

        assertEquals(
                List.of(
                        "buildcraft/code/a-Test.grug",
                        "buildcraft/code/b-Test.grug",
                        "jei/code/a-Test.grug"),
                executedTests(printed));
        assertEquals(3, ops.created);
        assertTrue(printed.contains("Only the tests of these mods run: jei, buildcraft"), printed);
        assertTrue(printed.contains("Running 3 tests..."), printed);
        assertTrue(printed.contains("ALL 3 TESTS PASSED"), printed);
    }

    @Test
    void aSelectedModWithoutTestsIsKnownAndRunsNothing() {
        String printed =
                capture(
                        () ->
                                runToEnd(
                                        selectingRunner(
                                                new FakeOps(),
                                                threeMods(),
                                                1,
                                                Set.of("examplemod"),
                                                new CoverageLog())));

        assertEquals(List.of(), executedTests(printed));
        assertTrue(printed.contains("ALL 0 TESTS PASSED"), printed);
    }

    @Test
    void repeatsOnlyTheSelectedTests() {
        FakeOps ops = new FakeOps();
        String printed =
                capture(
                        () ->
                                runToEnd(
                                        selectingRunner(
                                                ops,
                                                threeMods(),
                                                3,
                                                Set.of("jei"),
                                                new CoverageLog())));

        assertEquals(3, ops.created);
        assertEquals(
                List.of("jei/code/a-Test.grug", "jei/code/a-Test.grug", "jei/code/a-Test.grug"),
                executedTests(printed));
        assertEquals(1, printed.split("ALL 1 TESTS PASSED", -1).length - 1);
    }

    @Test
    void failsLoudlyOnASelectedModThatWasNotLoaded() {
        FakeOps ops = new FakeOps();
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                selectingRunner(
                                        ops,
                                        threeMods(),
                                        1,
                                        GrugTestRunner.parseMods("jei,buildcrft"),
                                        new CoverageLog()));

        assertTrue(e.getMessage().contains("'buildcrft'"), e.getMessage());
        // The message says which mods there are, so a typo is a one-line fix.
        assertTrue(
                e.getMessage().contains("buildcraft, coverage, examplemod, jei"), e.getMessage());
        assertEquals(0, ops.created);
    }

    @Test
    void aModNameIsMatchedWholeNotAsAPrefixOrAPathSegment() {
        for (String name : new String[] {"cover", "coverage/tests", "Coverage", "code"}) {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            selectingRunner(
                                    new FakeOps(),
                                    threeMods(),
                                    1,
                                    Set.of(name),
                                    new CoverageLog()));
        }
    }

    @Test
    void parsesTheCarryOverSelection() {
        // Unset, empty and blank all carry nothing over, which is the empty set.
        assertEquals(Set.of(), GrugTestRunner.parseCarryOverCoverageTests(null));
        assertEquals(Set.of(), GrugTestRunner.parseCarryOverCoverageTests(""));
        assertEquals(Set.of(), GrugTestRunner.parseCarryOverCoverageTests("  \t "));
        assertEquals(
                Set.of("coverage/tests/a-Test.grug"),
                GrugTestRunner.parseCarryOverCoverageTests("coverage/tests/a-Test.grug"));
        // Order as first seen, whitespace trimmed, duplicates dropped.
        assertEquals(
                List.of("coverage/tests/b-Test.grug", "coverage/tests/a-Test.grug"),
                List.copyOf(
                        GrugTestRunner.parseCarryOverCoverageTests(
                                " coverage/tests/b-Test.grug ,coverage/tests/a-Test.grug"
                                        + ",coverage/tests/b-Test.grug")));
    }

    @Test
    void refusesACarryOverSelectionWithAnEmptyEntry() {
        for (String value : new String[] {",", "a,", ",a", "a,,b", "a, ,b"}) {
            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class,
                            () -> GrugTestRunner.parseCarryOverCoverageTests(value));
            assertTrue(
                    e.getMessage().contains("GRUG_CI_COVERAGE_CARRY_OVER has an empty entry"),
                    e.getMessage());
        }
    }

    @Test
    void carriesOverOnlyTheNamedCoverageTestsAndSaysSo() {
        CoverageLog coverage = new CoverageLog();
        String printed =
                capture(
                        () ->
                                runToEnd(
                                        carryingRunner(
                                                new FakeOps(),
                                                threeMods(),
                                                1,
                                                Set.of(),
                                                Set.of("coverage/tests/b-Test.grug"),
                                                coverage)));

        // b-Test.grug is carried over and never started; the starts are the rest in the
        // runner's path order, and the line says that its assertions do not rerun, which is
        // the guarantee the carry-over loses.
        assertEquals(
                List.of(
                        "starting buildcraft/code/a-Test.grug",
                        "starting buildcraft/code/b-Test.grug",
                        "starting coverage/tests/a-Test.grug",
                        "starting jei/code/a-Test.grug",
                        "passed"),
                coverage.events);
        assertTrue(
                printed.contains(
                        "[GRUG CI] Carrying over 1 coverage test(s) from the base commit's"
                                + " recorded coverage, whose assertions do not rerun (#786):"
                                + " coverage/tests/b-Test.grug"),
                printed);
        assertTrue(printed.contains("Running 4 tests..."), printed);
        assertTrue(printed.contains("ALL 4 TESTS PASSED"), printed);
    }

    @Test
    void aCarryOverThatNamesANotLoadedCoverageTestFailsBeforeAnyTestRuns() {
        FakeOps ops = new FakeOps();
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                carryingRunner(
                                        ops,
                                        threeMods(),
                                        1,
                                        Set.of(),
                                        Set.of("jei/code/a-Test.grug"),
                                        new CoverageLog()));

        // The message says which test was named and which coverage tests there are, so a typo is a
        // one-line fix.
        assertTrue(e.getMessage().contains("'jei/code/a-Test.grug'"), e.getMessage());
        assertTrue(
                e.getMessage().contains("coverage/tests/a-Test.grug, coverage/tests/b-Test.grug"),
                e.getMessage());
        assertEquals(0, ops.created);
    }

    @Test
    void aCarryOverOfEveryCoverageTestStillRunsTheOthers() {
        CoverageLog coverage = new CoverageLog();
        String printed =
                capture(
                        () ->
                                runToEnd(
                                        carryingRunner(
                                                new FakeOps(),
                                                threeMods(),
                                                1,
                                                Set.of(),
                                                Set.of(
                                                        "coverage/tests/a-Test.grug",
                                                        "coverage/tests/b-Test.grug"),
                                                coverage)));

        assertEquals(
                List.of(
                        "starting buildcraft/code/a-Test.grug",
                        "starting buildcraft/code/b-Test.grug",
                        "starting jei/code/a-Test.grug",
                        "passed"),
                coverage.events);
        assertTrue(printed.contains("Running 3 tests..."), printed);
        assertTrue(printed.contains("ALL 3 TESTS PASSED"), printed);
    }

    @Test
    void theCoverageRecorderHearsOnlyTheSelectedMods() {
        CoverageLog coverage = new CoverageLog();
        runToEnd(
                selectingRunner(
                        new FakeOps(),
                        threeMods(),
                        1,
                        GrugTestRunner.parseMods("coverage,jei"),
                        coverage));

        assertEquals(
                List.of(
                        "starting coverage/tests/a-Test.grug",
                        "starting coverage/tests/b-Test.grug",
                        "starting jei/code/a-Test.grug",
                        "passed"),
                coverage.events);
    }

    @Test
    void theCoverageRecorderStillWorksWhenTheCountedModIsNotSelected() {
        CoverageLog coverage = new CoverageLog();
        GrugTestRunner runner =
                selectingRunner(
                        new FakeOps(), threeMods(), 1, GrugTestRunner.parseMods("jei"), coverage);
        runToEnd(runner);

        assertTrue(runner.isFinished());
        assertEquals(List.of("starting jei/code/a-Test.grug", "passed"), coverage.events);
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
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream().anyMatch(m -> m.equals("a plain failure")));
    }

    @Test
    void failsWhenCallingTheRunFunctionThrowsWithoutAMessage() {
        FakeOps ops = new FakeOps();
        ops.throwOnCall = new IllegalStateException();
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("IllegalStateException")));
    }

    @Test
    void failsWhenCreatingTheEntityThrowsWithoutAMessage() {
        FakeOps ops = new FakeOps();
        ops.throwOnCreate = new IllegalStateException();
        GrugTestRunner runner = runner(ops, Map.of("mymod/code/a-Test.grug", 1L));
        runToEnd(runner);
        assertTrue(runner.isFinished());
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("IllegalStateException")));
    }

    @Test
    void tellsTheRuntimeWhichModOwnsTheRunningTest() {
        // The owning mod decides what its tests may assert, so it has to be the mod holding the
        // running test rather than whichever mod a screenshot happens to render.
        FakeOps ops = new FakeOps();
        final List<String> owningMods = new ArrayList<>();
        ops.onCall = () -> owningMods.add(GrugTestState.currentTestMod);

        Map<String, Long> files = new LinkedHashMap<>();
        files.put("secondmod/code/b-Test.grug", 2L);
        files.put("firstmod/code/a-Test.grug", 1L);
        runToEnd(runner(ops, files));

        assertEquals(List.of("firstmod", "secondmod"), owningMods);
    }

    @Test
    void clearsACapturedOriginBetweenTests() {
        // The origin is captured per test, so the next test has to start from the player's current
        // position rather than inherit the one the previous test first asked for.
        GrugTestState.testOrigin = new Vec3(1.0, 2.0, 3.0);
        FakeOps ops = new FakeOps();
        ops.onCall =
                () -> {
                    assertNull(GrugTestState.testOrigin);
                    GrugTestState.testOrigin = new Vec3(4.0, 5.0, 6.0);
                };
        runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L)));

        assertNull(GrugTestState.testOrigin);
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

    /** Writes the record a test points the runner at, and returns its path. */
    private static Path recordFile(Path dir, String json) throws IOException {
        Path file = dir.resolve("test-times.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Points the runner at the record, and clears the point when the body is done. */
    private static void withRecordFile(Path file, Runnable body) {
        System.setProperty(GrugTestRunner.TEST_TIMES_FILE_PROPERTY, file.toString());
        try {
            body.run();
        } finally {
            System.clearProperty(GrugTestRunner.TEST_TIMES_FILE_PROPERTY);
        }
    }

    /** The "times" the record at file holds, for the assertions that read the run back. */
    private static Map<String, Long> timesIn(Path file) throws IOException {
        JsonObject object = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        Map<String, Long> times = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("times").entrySet()) {
            times.put(entry.getKey(), entry.getValue().getAsLong());
        }
        return times;
    }

    /** The "failed" the record at file holds, in the order the run wrote it. */
    private static List<String> failedIn(Path file) throws IOException {
        JsonObject object = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        List<String> failed = new ArrayList<>();
        for (JsonElement item : object.getAsJsonArray("failed")) {
            failed.add(item.getAsString());
        }
        return failed;
    }

    @Test
    void ordersTheTestsByTheRecordItReads() throws Exception {
        Path file =
                recordFile(
                        tempDir,
                        "{\"failed\": [], \"times\": {\"mymod/code/a-Test.grug\": 1000000,"
                                + " \"mymod/code/b-Test.grug\": 900000000}}");
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);

        withRecordFile(
                file,
                () -> {
                    String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));
                    assertEquals(
                            List.of("mymod/code/b-Test.grug", "mymod/code/a-Test.grug"),
                            executedTests(printed),
                            printed);
                });
    }

    @Test
    void runsThePreviouslyFailedTestFirstEvenWhenItIsNotTheSlowest() throws Exception {
        Path file =
                recordFile(
                        tempDir,
                        "{\"failed\": [\"mymod/code/a-Test.grug\"], \"times\": "
                                + "{\"mymod/code/a-Test.grug\": 1000000, "
                                + "\"mymod/code/b-Test.grug\": 900000000}}");
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);

        withRecordFile(
                file,
                () -> {
                    String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));
                    assertEquals(
                            List.of("mymod/code/a-Test.grug", "mymod/code/b-Test.grug"),
                            executedTests(printed),
                            printed);
                });
    }

    @Test
    void saysWhereTheTestOrderComesFrom() throws Exception {
        Path file =
                recordFile(tempDir, "{\"failed\": [], \"times\": {\"mymod/code/a-Test.grug\": 1}}");
        Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);

        withRecordFile(
                file,
                () -> {
                    String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));
                    assertTrue(
                            printed.contains("[GRUG CI] Test order: slowest first, from " + file),
                            printed);
                });
    }

    @Test
    void saysHowManyPreviouslyFailedTestsRunFirst() throws Exception {
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        Path one = recordFile(tempDir, "{\"failed\": [\"mymod/code/a-Test.grug\"], \"times\": {}}");
        Path two = tempDir.resolve("two.json");
        String json =
                "{\"failed\": [\"mymod/code/a-Test.grug\", \"mymod/code/b-Test.grug\"], "
                        + "\"times\": {}}";
        Files.write(two, json.getBytes(StandardCharsets.UTF_8));

        withRecordFile(
                one,
                () -> {
                    String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));
                    assertTrue(
                            printed.contains(
                                    "[GRUG CI] Test order: 1 previously failed test first,"
                                            + " then slowest first, from "
                                            + one),
                            printed);
                });
        withRecordFile(
                two,
                () -> {
                    String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));
                    assertTrue(
                            printed.contains(
                                    "[GRUG CI] Test order: 2 previously failed tests first,"
                                            + " then slowest first, from "
                                            + two),
                            printed);
                });
    }

    @Test
    void fallsBackToNameOrderWhenTheRecordCannotBeRead() throws Exception {
        // The record is data, not an invariant: a broken one or a missing one costs this run its
        // order, and the line says so where the run is read, instead of the suite being ordered by
        // noise.
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        files.put("othermod/code/a-Test.grug", 3L);
        for (Path file :
                new Path[] {
                    recordFile(tempDir, "definitely not a record"), tempDir.resolve("absent.json")
                }) {
            withRecordFile(
                    file,
                    () -> {
                        String printed =
                                capture(
                                        () ->
                                                runToEnd(
                                                        selectingRunner(
                                                                new FakeOps(),
                                                                files,
                                                                1,
                                                                GrugTestRunner.parseMods("mymod"),
                                                                new CoverageLog())));
                        assertTrue(
                                printed.contains(
                                        "The test order record at " + file + " could not be read"),
                                printed);
                        assertEquals(
                                List.of("mymod/code/a-Test.grug", "mymod/code/b-Test.grug"),
                                executedTests(printed),
                                printed);
                        assertTrue(printed.contains("ALL 2 TESTS PASSED"), printed);
                    });
        }
    }

    @Test
    void runsInNameOrderAndSaysNothingAboutTheOrderWhenThereIsNoRecord() {
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/b-Test.grug", 2L);
        files.put("mymod/code/a-Test.grug", 1L);

        String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));

        assertFalse(printed.contains("Test order:"), printed);
        assertEquals(
                List.of("mymod/code/a-Test.grug", "mymod/code/b-Test.grug"),
                executedTests(printed),
                printed);
    }

    @Test
    void anEmptyRecordNameIsTheSameAsNoRecord() {
        System.setProperty(GrugTestRunner.TEST_TIMES_FILE_PROPERTY, "");
        try {
            Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);
            String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));
            assertFalse(printed.contains("Test order:"), printed);
            assertTrue(printed.contains("ALL 1 TESTS PASSED"), printed);
        } finally {
            System.clearProperty(GrugTestRunner.TEST_TIMES_FILE_PROPERTY);
        }
    }

    @Test
    void anEmptyRecordKeepsNameOrderAndSaysNothingAboutIt() throws Exception {
        // The record exists but holds nothing, so the run's order is the name order it found, and
        // the run does not claim the record for it: an empty file is a place, not an order.
        Path file = recordFile(tempDir, "{\"failed\": [], \"times\": {}}");
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/b-Test.grug", 2L);
        files.put("mymod/code/a-Test.grug", 1L);

        withRecordFile(
                file,
                () -> {
                    String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));
                    assertFalse(printed.contains("Test order:"), printed);
                    assertEquals(
                            List.of("mymod/code/a-Test.grug", "mymod/code/b-Test.grug"),
                            executedTests(printed),
                            printed);
                });
    }

    @Test
    void writesTheTimesItMeasuredWhenTheRunPasses() throws Exception {
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        Map<String, Long> timePerTest = new LinkedHashMap<>();
        timePerTest.put("mymod/code/a-Test.grug", 3 * MS);
        timePerTest.put("mymod/code/b-Test.grug", 5 * MS);
        ops.pathsAre(files);
        ops.onCall = () -> clock.advance(timePerTest.get(ops.activePath));
        Path file = recordFile(tempDir, "{\"failed\": [], \"times\": {}}");

        withRecordFile(file, () -> capture(() -> runToEnd(timedRunner(ops, files, clock))));

        assertEquals(List.of(), failedIn(file));
        assertEquals(
                Map.of("mymod/code/a-Test.grug", 3 * MS, "mymod/code/b-Test.grug", 5 * MS),
                timesIn(file));
    }

    @Test
    void writesTheFailedTestAndTheTimeItTookWhenTheRunFails() throws Exception {
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        Map<String, Long> timePerTest = new LinkedHashMap<>();
        timePerTest.put("mymod/code/a-Test.grug", 4 * MS);
        timePerTest.put("mymod/code/b-Test.grug", 6 * MS);
        ops.pathsAre(files);
        ops.onCall =
                () -> {
                    clock.advance(timePerTest.get(ops.activePath));
                    if (ops.activePath.endsWith("b-Test.grug")) {
                        ops.completed = false;
                    }
                };
        Path file = recordFile(tempDir, "{\"failed\": [], \"times\": {}}");

        withRecordFile(file, () -> capture(() -> runToEnd(timedRunner(ops, files, clock))));

        assertEquals(List.of("mymod/code/b-Test.grug"), failedIn(file));
        assertEquals(
                Map.of("mymod/code/a-Test.grug", 4 * MS, "mymod/code/b-Test.grug", 6 * MS),
                timesIn(file));
    }

    @Test
    void keepsTheRecordedTimeOfATestTheRunDidNotReach() throws Exception {
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        files.put("mymod/code/c-Test.grug", 3L);
        ops.pathsAre(files);
        ops.onCall =
                () -> {
                    clock.advance(2 * MS);
                    if (ops.activePath.endsWith("b-Test.grug")) {
                        ops.completed = false;
                    }
                };
        Path file =
                recordFile(
                        tempDir,
                        "{\"failed\": [], \"times\": {\"mymod/code/a-Test.grug\": 8000000, "
                                + "\"mymod/code/b-Test.grug\": 2, "
                                + "\"mymod/code/c-Test.grug\": 1}}");

        withRecordFile(file, () -> capture(() -> runToEnd(timedRunner(ops, files, clock))));

        assertEquals(List.of("mymod/code/b-Test.grug"), failedIn(file));
        assertEquals(
                Map.of(
                        "mymod/code/a-Test.grug",
                        2 * MS,
                        "mymod/code/b-Test.grug",
                        2 * MS,
                        "mymod/code/c-Test.grug",
                        1L),
                timesIn(file));
    }

    @Test
    void keepsTheTimesOfTestsTheSelectionDidNotRun() throws Exception {
        // The run measured its own subset only, and the record it writes back is still a record of
        // every test the loader has, so the times of the mods it did not run survive the subset.
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("othermod/code/a-Test.grug", 2L);
        ops.pathsAre(files);
        ops.onCall = () -> clock.advance(3 * MS);
        Path file =
                recordFile(
                        tempDir,
                        "{\"failed\": [], \"times\": {\"othermod/code/a-Test.grug\": 7000000}}");

        withRecordFile(
                file,
                () ->
                        capture(
                                () ->
                                        runToEnd(
                                                new GrugTestRunner(
                                                        files,
                                                        List.of(),
                                                        ops,
                                                        clock,
                                                        new SweepLog(new ArrayList<>()),
                                                        1,
                                                        new CoverageLog(),
                                                        GrugTestRunner.parseMods("mymod")))));

        assertEquals(List.of(), failedIn(file));
        assertEquals(
                Map.of("mymod/code/a-Test.grug", 3 * MS, "othermod/code/a-Test.grug", 7_000_000L),
                timesIn(file));
    }

    @Test
    void dropsATestTheLoaderNoLongerHasFromTheRecordItWrites() throws Exception {
        // The record held a time for a test that is gone from the repository, so the record the
        // run writes back must not name it: a time is kept for each test the loader has, no more.
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/a-Block.grug", 9L);
        ops.pathsAre(files);
        ops.onCall = () -> clock.advance(MS);
        Path file =
                recordFile(
                        tempDir,
                        "{\"failed\": [\"ghostmod/code/gone-Test.grug\"], "
                                + "\"times\": {\"ghostmod/code/gone-Test.grug\": 1,"
                                + " \"mymod/code/a-Test.grug\": 2}}");

        withRecordFile(file, () -> capture(() -> runToEnd(timedRunner(ops, files, clock))));

        assertEquals(List.of(), failedIn(file));
        assertEquals(Map.of("mymod/code/a-Test.grug", MS), timesIn(file));
    }

    @Test
    void writesNothingWhenItWasNotPointedAtARecord() throws Exception {
        // No record file, no record written: the run keeps the repository's file exactly where the
        // refresh command, not the run, leaves it.
        FakeOps ops = new FakeOps();
        Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);

        capture(() -> runToEnd(runner(ops, files)));

        assertEquals(0, tempDir.toFile().list().length);
    }

    @Test
    void timesNoTestThatNeverStartedWhenTheRunFailsBeforeOneDoes() throws Exception {
        // A reload that never ends fails the run before the first test starts; the test it names
        // is one that was never started, so it took no time, and the record the run writes holds
        // none for it.
        FakeOps ops = new FakeOps();
        Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);
        ops.pathsAre(files);
        long[] now = {0};
        Path file = recordFile(tempDir, "{\"failed\": [], \"times\": {}}");

        withRecordFile(
                file,
                () -> {
                    GrugTestRunner runner =
                            new GrugTestRunner(
                                    files,
                                    List.of(),
                                    ops,
                                    () -> now[0],
                                    new GrugTestRunner.TestBoxBuilder() {
                                        @Override
                                        public void build(int radius) {}

                                        @Override
                                        public void sweepItems() {}

                                        @Override
                                        public boolean loaderReloading() {
                                            return true;
                                        }
                                    },
                                    1);
                    capture(() -> runner.tick(null));
                    now[0] = GrugTestRunner.RELOAD_DEADLINE_NANOS + 1;
                    capture(() -> runner.tick(null));
                });

        assertEquals(List.of("mymod/code/a-Test.grug"), failedIn(file));
        assertEquals(Map.of(), timesIn(file));
    }

    @Test
    void saysSoWhenTheUpdatedRecordCannotBeWritten() throws Exception {
        // The run read its record fine, and then the place the record lives goes away before the
        // run ends: the write fails, the run stays green, and the line says the update did not
        // land, because a data file is not a reason to fail a run.
        StepClock clock = new StepClock();
        FakeOps ops = new FakeOps();
        ops.onCall = () -> clock.advance(MS);
        Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);
        Path dir = Files.createTempDirectory(tempDir, "record");
        Path file = recordFile(dir, "{\"failed\": [], \"times\": {}}");

        withRecordFile(
                file,
                () -> {
                    GrugTestRunner runner = timedRunner(ops, files, clock);
                    capture(() -> runner.tick(null));
                    try {
                        Files.delete(file);
                        Files.delete(dir);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String printed = capture(() -> runToEnd(runner));
                    assertTrue(
                            printed.contains(
                                    "The updated test order record could not be written to "
                                            + file),
                            printed);
                    assertTrue(printed.contains("ALL 1 TESTS PASSED"), printed);
                });
    }

    @Test
    void aReferenceRunNeitherReadsNorWritesTheRecord() throws Exception {
        // The reference run loads the harness rather than the port, so its record would be of the
        // harness's tests and drop every one of the port's: the runner refuses to touch the
        // record on one, in either direction.
        Path file = recordFile(tempDir, "{\"failed\": [], \"times\": {}}");
        String before = Files.readString(file);
        System.setProperty(GrugReference.PROPERTY, "1");
        try {
            System.setProperty(GrugTestRunner.TEST_TIMES_FILE_PROPERTY, file.toString());
            Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);
            String printed = capture(() -> runToEnd(runner(new FakeOps(), files)));
            assertFalse(printed.contains("Test order:"), printed);
            assertTrue(printed.contains("ALL 1 TESTS PASSED"), printed);
        } finally {
            System.clearProperty(GrugReference.PROPERTY);
            System.clearProperty(GrugTestRunner.TEST_TIMES_FILE_PROPERTY);
        }
        assertEquals(before, Files.readString(file));
    }

    /** A room builder that logs what the runner asks of it, in order. */
    private static final class SweepLog implements GrugTestRunner.TestBoxBuilder {
        final List<String> log;

        SweepLog(List<String> log) {
            this.log = log;
        }

        @Override
        public void snapPlayer() {
            log.add("snap");
        }

        @Override
        public void build(int radius) {
            log.add("build");
        }

        @Override
        public void sweepItems() {
            log.add("sweep");
        }
    }

    /**
     * A room builder whose clock logs every speed it is asked for, answers as told, and runs at
     * most {@code maxSpeed} times real time.
     */
    private static final class ClockLog implements GrugTestRunner.TestBoxBuilder {
        final List<String> log;
        final boolean supported;
        final double maxSpeed;

        ClockLog(List<String> log, boolean supported, double maxSpeed) {
            this.log = log;
            this.supported = supported;
            this.maxSpeed = maxSpeed;
        }

        @Override
        public void build(int radius) {
            log.add("build");
        }

        @Override
        public void sweepItems() {}

        @Override
        public double maxGameSpeed() {
            return maxSpeed;
        }

        @Override
        public boolean setGameSpeed(double speed) {
            log.add("speed " + (long) speed);
            return supported;
        }
    }

    private static GrugTestRunner clockRunner(
            FakeOps ops, Map<String, Long> files, GrugTestRunner.TestBoxBuilder box, int max) {
        return new GrugTestRunner(
                files,
                List.of(),
                ops,
                System::nanoTime,
                box,
                1,
                new GrugTestRunner.CoverageRecorder() {
                    @Override
                    public void testStarting(String testPath) {}

                    @Override
                    public void runPassed() {}
                },
                Set.of(),
                max,
                Set.of());
    }

    /** Ticks the runner until it finishes or has been ticked {@code limit} times. */
    private void runFor(GrugTestRunner runner, int limit) {
        for (int i = 0; i < limit && !runner.isFinished(); i++) {
            runner.tick(null);
        }
    }

    @Test
    void everyTestRunsFastAfterTheRoomAndGetsRealTimeBackWhenItEnds() {
        FakeOps ops = new FakeOps();
        List<String> log = new ArrayList<>();
        ops.onCall = () -> log.add("tick");
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(log, true, 10),
                        GrugTestRunner.MAX_GAME_SPEED);

        String printed = capture(() -> runToEnd(runner));

        assertEquals(
                List.of("build", "speed 10", "tick", "speed 1"),
                log,
                "The room is built at real time, the test ticks fast, and real time returns.");
        assertTrue(printed.contains("Game speed 10x from mymod/code/a-Test.grug"), printed);
    }

    @Test
    void theSpeedGoesBackToRealTimeBetweenTests() {
        FakeOps ops = new FakeOps();
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        ops.pathsAre(files);
        List<String> log = new ArrayList<>();
        GrugTestRunner runner =
                clockRunner(ops, files, new ClockLog(log, true, 10), GrugTestRunner.MAX_GAME_SPEED);

        String printed = capture(() -> runToEnd(runner));

        assertEquals(
                List.of("build", "speed 10", "speed 1", "build", "speed 10", "speed 1"),
                log,
                "Each test builds its room at real time and runs fast, and nothing carries over.");
        assertEquals(
                1,
                printed.lines().filter(l -> l.contains("Game speed 10x")).count(),
                "The log says it once for the run: " + printed);
    }

    @Test
    void aFailingTestStillGetsRealTimeBack() {
        FakeOps ops = new FakeOps();
        List<String> log = new ArrayList<>();
        ops.throwOnCall = new RuntimeException("boom");
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(log, true, 10),
                        GrugTestRunner.MAX_GAME_SPEED);

        capture(() -> runToEnd(runner));

        assertTrue(runner.isFinished());
        assertEquals(List.of("build", "speed 10", "speed 1"), log);
    }

    @Test
    void aTestThatExpectsAnErrorStillGetsRealTimeBack() {
        FakeOps ops = new FakeOps();
        List<String> log = new ArrayList<>();
        ops.completed = false;
        ops.onCall =
                () -> {
                    GrugTestState.testExpectedError = "oops";
                    GrugTestState.testRuntimeErrors.add("oops happened");
                };
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(log, true, 10),
                        GrugTestRunner.MAX_GAME_SPEED);

        capture(() -> runToEnd(runner));

        assertEquals(List.of("build", "speed 10", "speed 1"), log);
    }

    @Test
    void aLoaderThatRefusesTheSpeedRunsTheTestAtRealTimeAndSaysSo() {
        FakeOps ops = new FakeOps();
        List<String> log = new ArrayList<>();
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(log, false, 10),
                        GrugTestRunner.MAX_GAME_SPEED);

        String printed = capture(() -> runToEnd(runner));

        assertEquals(
                List.of("build", "speed 10"), log, "Nothing was applied, so nothing is restored.");
        assertTrue(runner.isFinished());
        assertTrue(GrugErrors.runtimeErrorQueue.stream().noneMatch(m -> m.contains("FAIL")));
        assertTrue(
                printed.contains(
                        "mymod/code/a-Test.grug could not be run at game speed 10x, so it runs"
                                + " at 1x"),
                printed);
    }

    @Test
    void aLoaderThatCannotSpeedUpIsNeverAsked() {
        FakeOps ops = new FakeOps();
        List<String> log = new ArrayList<>();
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(log, true, 1),
                        GrugTestRunner.MAX_GAME_SPEED);

        String printed = capture(() -> runToEnd(runner));

        assertEquals(List.of("build"), log);
        assertFalse(printed.contains("game speed"), printed);
        assertFalse(printed.contains("Game speed"), printed);
    }

    @Test
    void theLimitCapsTheSpeedAndOneKeepsEveryTestAtRealTime() {
        FakeOps capped = new FakeOps();
        List<String> cappedLog = new ArrayList<>();
        GrugTestRunner cappedRunner =
                clockRunner(
                        capped,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(cappedLog, true, 10),
                        4);
        capture(() -> runToEnd(cappedRunner));
        assertEquals(List.of("build", "speed 4", "speed 1"), cappedLog);

        FakeOps off = new FakeOps();
        List<String> offLog = new ArrayList<>();
        GrugTestRunner offRunner =
                clockRunner(
                        off,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(offLog, true, 10),
                        1);
        capture(() -> runToEnd(offRunner));
        assertEquals(List.of("build"), offLog, "A limit of 1 asks for nothing.");
    }

    @Test
    void theLoaderPicksTheSpeedWhenTheLimitIsAboveIt() {
        FakeOps ops = new FakeOps();
        List<String> log = new ArrayList<>();
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(log, true, 7),
                        GrugTestRunner.MAX_GAME_SPEED);

        capture(() -> runToEnd(runner));

        assertEquals(List.of("build", "speed 7", "speed 1"), log);
    }

    @Test
    void theTickBudgetIsATimeBudgetSoItGrowsWithTheSpeed() {
        FakeOps ops = new FakeOps();
        int[] calls = {0};
        ops.onCall =
                () -> {
                    calls[0]++;
                    GrugTestState.testNotDone = true;
                };
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(new ArrayList<>(), true, 10),
                        GrugTestRunner.MAX_GAME_SPEED);

        capture(() -> runFor(runner, 5000));

        assertTrue(runner.isFinished());
        assertEquals(
                GrugTestRunner.DEFAULT_MAX_TEST_TICKS * 10,
                calls[0],
                "A test that never stops is given the default budget times the speed.");
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(
                                m ->
                                        m.contains(
                                                "Test exceeded 2000 ticks (200 ticks of real time,"
                                                        + " at game speed 10x) without"
                                                        + " finishing")),
                GrugErrors.runtimeErrorQueue.toString());
    }

    @Test
    void aRaisedTickBudgetGrowsWithTheSpeedToo() {
        FakeOps ops = new FakeOps();
        int[] calls = {0};
        ops.onInit = () -> GrugTestState.maxTestTicks = 300;
        ops.onCall =
                () -> {
                    calls[0]++;
                    GrugTestState.testNotDone = true;
                };
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(new ArrayList<>(), true, 10),
                        GrugTestRunner.MAX_GAME_SPEED);

        capture(() -> runFor(runner, 5000));

        assertEquals(3000, calls[0]);
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(m -> m.contains("Test exceeded 3000 ticks (300 ticks of real")),
                GrugErrors.runtimeErrorQueue.toString());
    }

    @Test
    void theTickBudgetStaysItselfAtRealTime() {
        FakeOps ops = new FakeOps();
        int[] calls = {0};
        ops.onCall =
                () -> {
                    calls[0]++;
                    GrugTestState.testNotDone = true;
                };
        GrugTestRunner runner =
                clockRunner(
                        ops,
                        Map.of("mymod/code/a-Test.grug", 1L),
                        new ClockLog(new ArrayList<>(), true, 10),
                        1);

        capture(() -> runFor(runner, 5000));

        assertEquals(GrugTestRunner.DEFAULT_MAX_TEST_TICKS, calls[0]);
        assertTrue(
                GrugErrors.runtimeErrorQueue.stream()
                        .anyMatch(
                                m ->
                                        m.contains("Test exceeded 200 ticks without finishing")
                                                && !m.contains("game speed")),
                GrugErrors.runtimeErrorQueue.toString());
    }

    @Test
    void theSeamDefaultsToAClockThatCannotBeSpedUp() {
        SweepLog box = new SweepLog(new ArrayList<>());
        assertFalse(box.setGameSpeed(10));
        assertEquals(1, box.maxGameSpeed());
    }

    @Test
    void parsesTheLimitOnGameSpeed() {
        assertEquals(GrugTestRunner.MAX_GAME_SPEED, GrugTestRunner.parseMaxGameSpeed(null));
        assertEquals(GrugTestRunner.MAX_GAME_SPEED, GrugTestRunner.parseMaxGameSpeed(""));
        assertEquals(1, GrugTestRunner.parseMaxGameSpeed("1"));
        assertEquals(100, GrugTestRunner.parseMaxGameSpeed("100"));
        for (String bad : new String[] {"0", "-3", "101", "2.5", "fast", " "}) {
            assertThrows(
                    IllegalStateException.class, () -> GrugTestRunner.parseMaxGameSpeed(bad), bad);
        }
    }

    @Test
    void anAdapterThatDoesNotOverrideTheClockCannotSpeedItUp() throws Throwable {
        ModLoaderAdapter adapter =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                ModLoaderAdapter.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) ->
                                        java.lang.reflect.InvocationHandler.invokeDefault(
                                                proxy, method, args));

        assertFalse(adapter.setGameSpeed(10));
        assertEquals(1, adapter.maxGameSpeed());
    }

    @Test
    void startsNoTestWhileTheLoaderIsReloading() {
        FakeOps ops = new FakeOps();
        Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);
        ops.pathsAre(files);
        boolean[] reloading = {true};
        GrugTestRunner runner =
                new GrugTestRunner(
                        files,
                        List.of(),
                        ops,
                        System::nanoTime,
                        new GrugTestRunner.TestBoxBuilder() {
                            @Override
                            public void build(int radius) {}

                            @Override
                            public void sweepItems() {}

                            @Override
                            public boolean loaderReloading() {
                                return reloading[0];
                            }
                        },
                        1);
        String held = capture(() -> runner.tick(null));
        assertFalse(held.contains("Executing test"), held);

        reloading[0] = false;
        String started = capture(() -> runner.tick(null));
        assertTrue(started.contains("Executing test"), started);
    }

    @Test
    void closesTheOpenScreenOnceBeforeTheFirstTestStarts() {
        // A client left alone while the world loaded can have put its menu up over the world, which
        // pauses the world. The runner closes it before the first test and not again after, since a
        // test that opens a screen is the one that closes it. See #655.
        FakeOps ops = new FakeOps();
        Map<String, Long> files =
                new java.util.TreeMap<>(
                        Map.of("mymod/code/a-Test.grug", 1L, "mymod/code/b-Test.grug", 2L));
        ops.pathsAre(files);
        List<String> log = new java.util.ArrayList<>();
        ops.onCall = () -> log.add("tick");
        GrugTestRunner runner =
                new GrugTestRunner(
                        files,
                        List.of(),
                        ops,
                        System::nanoTime,
                        new GrugTestRunner.TestBoxBuilder() {
                            @Override
                            public void build(int radius) {
                                log.add("build");
                            }

                            @Override
                            public void sweepItems() {}

                            @Override
                            public void closeScreenBeforeFirstTest() {
                                log.add("close");
                            }
                        },
                        1);

        capture(() -> runToEnd(runner));

        assertEquals("close", log.get(0), log.toString());
        assertEquals(1, log.stream().filter("close"::equals).count(), log.toString());
        assertTrue(log.indexOf("close") < log.indexOf("build"), log.toString());
    }

    @Test
    void failsTheRunWhenTheLoaderNeverFinishesItsReload() {
        FakeOps ops = new FakeOps();
        Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);
        ops.pathsAre(files);
        long[] now = {0};
        GrugTestRunner runner =
                new GrugTestRunner(
                        files,
                        List.of(),
                        ops,
                        () -> now[0],
                        new GrugTestRunner.TestBoxBuilder() {
                            @Override
                            public void build(int radius) {}

                            @Override
                            public void sweepItems() {}

                            @Override
                            public boolean loaderReloading() {
                                return true;
                            }
                        },
                        1);
        String first = capture(() -> runner.tick(null));
        assertTrue(first.contains("Waiting for the loader's resource reload"), first);
        assertTrue(first.contains("mymod/code/a-Test.grug"), first);

        now[0] = GrugTestRunner.RELOAD_DEADLINE_NANOS;
        String again = capture(() -> runner.tick(null));
        assertEquals("", again);
        assertFalse(runner.isFinished());

        now[0] = GrugTestRunner.RELOAD_DEADLINE_NANOS + 1;
        String failed = capture(() -> runner.tick(null));
        assertTrue(failed.contains("[GRUG CI] FAIL mymod/code/a-Test.grug"), failed);
        assertTrue(failed.contains("never finished"), failed);
        assertTrue(runner.isFinished());
    }

    /** A room builder whose client holds stale block entities for as long as the test says. */
    private static GrugTestRunner.TestBoxBuilder staleBlockEntities(boolean[] stale) {
        return new GrugTestRunner.TestBoxBuilder() {
            @Override
            public void build(int radius) {}

            @Override
            public void sweepItems() {}

            @Override
            public boolean staleBlockEntitiesRemain() {
                return stale[0];
            }
        };
    }

    @Test
    void holdsTheFirstTickWhileTheClientStillHoldsABlockEntityOfTheLastTest() {
        FakeOps ops = new FakeOps();
        Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);
        ops.pathsAre(files);
        int[] ticks = {0};
        ops.onCall = () -> ticks[0]++;
        boolean[] stale = {true};
        long[] now = {0};
        GrugTestRunner runner =
                new GrugTestRunner(
                        files, List.of(), ops, () -> now[0], staleBlockEntities(stale), 1);

        // A short wait is routine and says nothing.
        String held = capture(() -> runner.tick(null));
        assertTrue(held.contains("Executing test: mymod/code/a-Test.grug"), held);
        assertFalse(held.contains("Waiting for the client to drop"), held);
        assertEquals(0, ticks[0]);

        now[0] = GrugTestRunner.STALE_BLOCK_ENTITY_REPORT_AFTER_NANOS;
        assertEquals("", capture(() -> runner.tick(null)));

        // A long one says so, once, and the test is not started a second time.
        now[0] = GrugTestRunner.STALE_BLOCK_ENTITY_REPORT_AFTER_NANOS + 1;
        String reported = capture(() -> runner.tick(null));
        assertTrue(reported.contains("Waiting for the client to drop"), reported);
        assertTrue(reported.contains("mymod/code/a-Test.grug"), reported);
        assertEquals("", capture(() -> runner.tick(null)));
        assertEquals(0, ticks[0]);
        assertEquals(1, ops.created);

        stale[0] = false;
        capture(() -> runner.tick(null));
        assertEquals(1, ticks[0]);
        assertEquals(1, ops.created);
        assertFalse(runner.isFinished());
    }

    @Test
    void failsTheRunWhenTheClientNeverDropsTheLastTestsBlockEntities() {
        FakeOps ops = new FakeOps();
        Map<String, Long> files = Map.of("mymod/code/a-Test.grug", 1L);
        ops.pathsAre(files);
        long[] now = {0};
        GrugTestRunner runner =
                new GrugTestRunner(
                        files,
                        List.of(),
                        ops,
                        () -> now[0],
                        staleBlockEntities(new boolean[] {true}),
                        1);
        capture(() -> runner.tick(null));

        now[0] = GrugTestRunner.STALE_BLOCK_ENTITY_DEADLINE_NANOS;
        capture(() -> runner.tick(null));
        assertFalse(runner.isFinished());

        now[0] = GrugTestRunner.STALE_BLOCK_ENTITY_DEADLINE_NANOS + 1;
        String failed = capture(() -> runner.tick(null));
        assertTrue(failed.contains("[GRUG CI] FAIL mymod/code/a-Test.grug"), failed);
        assertTrue(failed.contains("still held a block entity"), failed);
        assertTrue(runner.isFinished());
        assertEquals(1, ops.destroyed);
    }

    private static List<String> runWithSweepLog(FakeOps ops, Map<String, Long> files) {
        List<String> log = new ArrayList<>();
        ops.pathsAre(files);
        GrugTestRunner runner =
                new GrugTestRunner(files, List.of(), ops, System::nanoTime, new SweepLog(log), 1);
        // Both outcomes print to stdout, which this test has no use for.
        PrintStream old = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            for (int i = 0;
                    i < GrugTestRunner.DEFAULT_MAX_TEST_TICKS + 5 && !runner.isFinished();
                    i++) {
                runner.tick(null);
            }
        } finally {
            System.setOut(old);
        }
        return log;
    }

    @Test
    void sweepsDroppedItemsInTheTickEveryPassingTestEnds() {
        // The room build sweeps when the next test starts, which can be many ticks later. The end
        // of each test sweeps too, so the last items it dropped cannot outlive it. See #323.
        Map<String, Long> files = new LinkedHashMap<>();
        files.put("mymod/code/a-Test.grug", 1L);
        files.put("mymod/code/b-Test.grug", 2L);
        List<String> log = runWithSweepLog(new FakeOps(), files);
        assertEquals(List.of("snap", "build", "sweep", "snap", "build", "sweep"), log);
    }

    @Test
    void sweepsDroppedItemsWhenATestEndsWithItsExpectedError() {
        FakeOps ops = new FakeOps();
        ops.onCall = () -> abortWithHostFunctionError(ops, "boom", "boom happened");
        List<String> log = runWithSweepLog(ops, Map.of("mymod/code/a-Test.grug", 1L));
        assertEquals(List.of("snap", "build", "sweep"), log);
    }

    @Test
    void sweepsDroppedItemsWhenATestFails() {
        FakeOps ops = new FakeOps();
        ops.completed = false;
        List<String> log = runWithSweepLog(ops, Map.of("mymod/code/a-Test.grug", 1L));
        assertEquals(List.of("snap", "build", "sweep"), log);
    }

    @Test
    void sweepsOnlyOnceAtTheEndOfATestThatAsksForMoreTicks() {
        FakeOps ops = new FakeOps();
        int[] calls = {0};
        ops.onCall = () -> GrugTestState.testNotDone = ++calls[0] < 3;
        List<String> log = runWithSweepLog(ops, Map.of("mymod/code/a-Test.grug", 1L));
        assertEquals(List.of("snap", "build", "sweep"), log);
    }

    @Test
    void restoresThePlayerWhenATestPasses() {
        // The runner snapped the player to their block centre at the start of the test, so it has
        // to put them back for it rather than rely on the test remembering to. See #393 and #408.
        String printed =
                capture(
                        () ->
                                runToEnd(
                                        runner(
                                                new FakeOps(),
                                                Map.of("mymod/code/a-Test.grug", 1L))));

        assertTrue(printed.contains("[GRUG CI] PASS mymod/code/a-Test.grug"));
        assertEquals(List.of("restore"), playerRestores);
    }

    @Test
    void restoresThePlayerWhenATestFails() {
        // A failed assertion or a screenshot mismatch aborts the script, and the player the runner
        // had already snapped must not be left that way for the next test.
        FakeOps ops = new FakeOps();
        ops.completed = false;

        String printed = capture(() -> runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L))));

        assertTrue(printed.contains("[GRUG CI] FAIL mymod/code/a-Test.grug"));
        assertEquals(List.of("restore"), playerRestores);
    }

    @Test
    void restoresThePlayerWhenATestAbortsOnAnError() {
        // An error thrown out of the run call ends the test just as abruptly as a failed assertion.
        FakeOps ops = new FakeOps();
        ops.throwOnCall = new IllegalStateException("boom");

        String printed = capture(() -> runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L))));

        assertTrue(printed.contains("[GRUG CI] FAIL mymod/code/a-Test.grug"));
        assertTrue(printed.contains("[GRUG CI] boom"));
        assertEquals(List.of("restore"), playerRestores);
    }

    @Test
    void restoresThePlayerWhenATestEndsWithItsExpectedError() {
        // An expect_error test that sees its error is a pass, and it restores like any other.
        FakeOps ops = new FakeOps();
        ops.onCall = () -> abortWithHostFunctionError(ops, "boom", "boom happened");

        String printed = capture(() -> runToEnd(runner(ops, Map.of("mymod/code/a-Test.grug", 1L))));

        assertTrue(printed.contains("[GRUG CI] PASS mymod/code/a-Test.grug (expected error)"));
        assertEquals(List.of("restore"), playerRestores);
    }
}
