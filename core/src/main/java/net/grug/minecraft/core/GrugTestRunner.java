package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Drives every {@code *-Test.grug} file, one real game tick at a time.
 *
 * <p>This used to be a single blocking call that ran each test to completion, which meant zero real
 * ticks (and so zero rendered frames) ever elapsed during a test. That's fine for pure logic tests,
 * but useless for anything that needs to screenshot actual rendered output. A test now spreads
 * itself over as many ticks as it needs by calling {@code Test.not_done()}, and real frames render
 * between those invocations via the normal game loop.
 */
public class GrugTestRunner {
    /** How many ticks a single test may run for before we give up on it. */
    // TODO: Allow individual tests to override this budget.
    public static final int DEFAULT_MAX_TEST_TICKS = 200; // 10 seconds at 20 ticks/sec

    /**
     * The grug operations the runner needs, so a Java test can drive the runner's state machine
     * without the native library attached.
     */
    public interface TestEntityOps {
        long createEntity(long fileId);

        long getExportFnId(String entityType, String fnName);

        boolean callExportFn(long entityHandle, long exportFnId);

        void destroyEntity(long entityHandle);
    }

    private static final TestEntityOps NATIVE_OPS =
            new TestEntityOps() {
                @Override
                public long createEntity(long fileId) {
                    return Grug.createEntity(fileId);
                }

                @Override
                public long getExportFnId(String entityType, String fnName) {
                    return Grug.getExportFnId(entityType, fnName);
                }

                @Override
                public boolean callExportFn(long entityHandle, long exportFnId) {
                    return Grug.callExportFn(entityHandle, exportFnId);
                }

                @Override
                public void destroyEntity(long entityHandle) {
                    Grug.destroyEntity(entityHandle);
                }
            };

    /**
     * Builds the room a test runs in. A seam like {@link TestEntityOps}: the unit tests drive the
     * runner without a game to build anything in.
     */
    public interface TestBoxBuilder {
        void build(int radius);
    }

    private final TestEntityOps ops;
    private final LongSupplier clock;
    private final TestBoxBuilder boxBuilder;
    private final List<Map.Entry<String, Long>> tests;
    private final int totalCount;

    private int nextTestIndex = 0;
    private int passedCount = 0;

    /** 0 when no test is active, meaning "start the next one". */
    private long currentEntityHandle = 0;

    private long currentRunFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private int currentTick = 0;

    /** When the running test started, so its duration can be worked out when it ends. */
    private long currentTestStartNanos = 0;

    /**
     * How long each test that finished took, in the order they ran. A path is unique because the
     * runner took it from a map's keys, so it doubles as the key here.
     */
    private final Map<String, Long> testNanos = new LinkedHashMap<>();

    private boolean finished = false;

    public GrugTestRunner() {
        this(
                Grug.fileIds,
                GrugScreenshots.validateReferenceTrees(
                        GrugCore.getAdapter().getGrugModsDirectory()),
                NATIVE_OPS,
                System::nanoTime,
                radius -> GrugCore.getAdapter().buildTestBox(radius));
    }

    /** Visible for tests: builds a runner over the given files without touching GrugCore. */
    public GrugTestRunner(
            Map<String, Long> fileIds, List<String> referenceErrors, TestEntityOps ops) {
        this(fileIds, referenceErrors, ops, System::nanoTime, radius -> {});
    }

    /**
     * Visible for tests: the same, with the clock that measures how long each test took. A seam
     * because the only thing worth asserting about those durations is their order, and ordering two
     * real durations means either sleeping long enough to be sure or racing.
     */
    public GrugTestRunner(
            Map<String, Long> fileIds,
            List<String> referenceErrors,
            TestEntityOps ops,
            LongSupplier clock) {
        this(fileIds, referenceErrors, ops, clock, radius -> {});
    }

    /**
     * Visible for tests: the same, with the room builder the loaders wire to the game. The other
     * constructors build no room, because a unit test has no world to build one in.
     */
    public GrugTestRunner(
            Map<String, Long> fileIds,
            List<String> referenceErrors,
            TestEntityOps ops,
            LongSupplier clock,
            TestBoxBuilder boxBuilder) {
        this.ops = ops;
        this.clock = clock;
        this.boxBuilder = boxBuilder;

        // A run refuses to start on a malformed screenshots/ tree. This is the same check CI hits,
        // so an author sees every violation locally before it ever reaches a pull request.
        if (!referenceErrors.isEmpty()) {
            this.tests = new ArrayList<>();
            this.totalCount = 0;
            this.finished = true;
            synchronized (Grug.runtimeErrorQueue) {
                Grug.runtimeErrorQueue.add("FAIL screenshot reference directories");
                System.out.println("[GRUG CI] FAIL screenshot reference directories");
                for (String error : referenceErrors) {
                    Grug.runtimeErrorQueue.add(error);
                    System.out.println("[GRUG CI] " + error);
                }
            }
            return;
        }

        this.tests = new ArrayList<>();
        // Anything already queued was reported during startup, before any test ran, so clearing it
        // here keeps the chat from showing the same message a second time. An expect_error test
        // matches testRuntimeErrors instead, which only a test that set an expectation can write
        // to and which the runner clears before each Test.run() call, so a startup message cannot
        // answer a test.
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.clear();
        }
        for (Map.Entry<String, Long> entry : fileIds.entrySet()) {
            if (entry.getKey().endsWith(GrugFileIndex.TEST_SUFFIX)) {
                tests.add(entry);
            }
        }
        // Tests share one world, so run them in a stable order. Otherwise a logic test's placed
        // blocks and spawned entities can land in a screenshot test's frame, and HashMap iteration
        // order would decide whether the run passes.
        tests.sort(Map.Entry.comparingByKey());
        this.totalCount = tests.size();

        String startMsg =
                "Running " + totalCount + " " + (totalCount == 1 ? "test" : "tests") + "...";
        System.out.println("[GRUG CI] " + startMsg);

        synchronized (Grug.printQueue) {
            Grug.printQueue.add(startMsg);
        }
    }

    /**
     * Does exactly one unit of work: starts the next test if none is active, then makes exactly one
     * {@code Test.run()} call for it. Meant to be called once per real Minecraft tick.
     */
    public void tick(Object player) {
        if (finished) return;

        if (currentEntityHandle == 0 && !startNextTest()) {
            // Nothing left to run.
            // Marked before the dump rather than after it, so that a dump which throws stops the
            // loaders from queueing this runner again and repeating one failure once per tick.
            finished = true;
            if (passedCount == totalCount) {
                // Before announcing the run, because run-loader.sh reads that line as the whole run
                // succeeding and stops watching: anything that goes wrong after it is never looked
                // at, so a failed dump would have left the run green with no coverage data behind
                // it. Dumping here also makes the line mean what it says, which is that the tests
                // passed and their coverage was recorded. The times go first for the same reason,
                // and before the dump because a dump that throws is exactly when they are wanted.
                printTestTimes();
                GrugCoverage.dump();
                System.out.println("[GRUG CI] ALL " + totalCount + " TESTS PASSED");
            }
            return;
        }

        String path = tests.get(nextTestIndex).getKey();

        Grug.currentTestTick = currentTick;
        Grug.testNotDone = false;
        Grug.currentTestMod = owningMod(path);
        // The engine clears its error flag at the start of every export function call and stops the
        // script at the first error, so the errors this call raises are the only ones that can
        // answer this call's expect_error. Anything an earlier tick left behind goes here rather
        // than standing in for an error this test never produced.
        synchronized (Grug.testRuntimeErrors) {
            Grug.testRuntimeErrors.clear();
        }

        boolean completed;
        try {
            completed = ops.callExportFn(currentEntityHandle, currentRunFnId);
        } catch (Exception e) {
            // Same fail-fast behavior as before: the first failing test aborts the whole run.
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Broken grug invariant: ")) {
                msg = msg.substring(23);
            } else if (msg == null) {
                msg = e.toString();
            }
            fail(path, msg);
            return;
        }

        if (!completed) {
            if (Grug.testExpectedError != null) {
                // The script aborted because a host function errored. Accept it only if the message
                // matches what the test asked to see, so a test cannot pass on the wrong error. The
                // message comes from the record the error was written to on this thread, not from
                // the chat queue, so nothing that runs on another thread can take it first.
                String actual;
                synchronized (Grug.testRuntimeErrors) {
                    actual = String.join("\n", Grug.testRuntimeErrors);
                    Grug.testRuntimeErrors.clear();
                }
                // The same message is also waiting for the chat, and a red message about an error
                // this test asked for is noise, so it is not shown. That is all this drain is for,
                // and clearing the chat queue cannot affect the match above.
                synchronized (Grug.runtimeErrorQueue) {
                    Grug.runtimeErrorQueue.clear();
                }
                Grug.printQueue.clear();
                if (!actual.contains(Grug.testExpectedError)) {
                    fail(
                            path,
                            "Expected an error containing '"
                                    + Grug.testExpectedError
                                    + "', but got: "
                                    + actual);
                    return;
                }
                System.out.println("[GRUG CI] PASS " + path + " (expected error)");
                passedCount++;
                recordTestTime(path);
                destroyCurrentEntity();
                nextTestIndex++;
                currentTick = 0;
                return;
            }
            // The script aborted partway through: either a host function reported a runtime error
            // (Grug.hostFunctionErrorHappened, which is how the graphics functions refuse an
            // unsupported loader or an out-of-bounds crop) or the script itself errored. Either way
            // it did not run to completion, so it certainly did not pass. The reason has already
            // been logged and queued for chat by Grug.onRuntimeError.
            fail(
                    path,
                    "The test script reported a runtime error and stopped early. "
                            + "See the error above for the reason.");
            return;
        }

        if (Grug.testNotDone) {
            // currentTick is this invocation's tick, so asking for one more means asking for
            // currentTick + 1. Refusing that once it reaches the cap is what bounds a test to
            // DEFAULT_MAX_TEST_TICKS run() calls, on ticks 0 through DEFAULT_MAX_TEST_TICKS - 1.
            if (currentTick + 1 >= DEFAULT_MAX_TEST_TICKS) {
                // Deliberately loud: a test that keeps asking for more ticks should hang for the
                // whole budget and then say so, rather than quietly passing or running forever.
                fail(
                        path,
                        "Test exceeded "
                                + DEFAULT_MAX_TEST_TICKS
                                + " ticks without finishing (did it forget to stop calling"
                                + " Test.not_done()?)");
                return;
            }
            currentTick++;
            return;
        }

        // The test stopped asking for ticks, so it's done.
        if (Grug.testExpectedError != null) {
            fail(
                    path,
                    "Test expected an error containing '"
                            + Grug.testExpectedError
                            + "', but it completed without one.");
            return;
        }
        System.out.println("[GRUG CI] PASS " + path);

        synchronized (Grug.printQueue) {
            Grug.printQueue.add("\u00A7aPASS " + path);
        }
        passedCount++;
        recordTestTime(path);

        destroyCurrentEntity();
        nextTestIndex++;
        currentTick = 0;
    }

    /**
     * Notes how long the test that just finished took.
     *
     * <p>Wall clock rather than ticks, because the question this answers is how long a run waits. A
     * test's tick count says nothing about that: one that spreads itself over two hundred ticks
     * waiting for a frame is quick, and one that does a single expensive call in a single tick is
     * slow.
     */
    private void recordTestTime(String path) {
        testNanos.put(path, clock.getAsLong() - currentTestStartNanos);
    }

    /**
     * Prints what every test took, slowest first, because that is the order which says something: a
     * reader looking for the one to fix starts at the top. Whole milliseconds, since nothing below
     * that is worth acting on and the extra digits would only make the column harder to scan.
     *
     * <p>Silent when no test ran, because a heading over no rows is noise.
     */
    private void printTestTimes() {
        List<Map.Entry<String, Long>> slowestFirst = new ArrayList<>(testNanos.entrySet());
        if (slowestFirst.isEmpty()) {
            // A heading over no rows, on a run that found no tests to run, says nothing.
            return;
        }
        slowestFirst.sort((first, second) -> Long.compare(second.getValue(), first.getValue()));

        long totalNanos = 0;
        int width = 1;
        for (Map.Entry<String, Long> entry : slowestFirst) {
            totalNanos += entry.getValue();
            width = Math.max(width, Long.toString(millis(entry.getValue())).length());
        }

        System.out.println(
                "[GRUG CI] Test times, slowest first ("
                        + slowestFirst.size()
                        + " tests, "
                        + millis(totalNanos)
                        + "ms total):");
        for (Map.Entry<String, Long> entry : slowestFirst) {
            System.out.println(
                    "[GRUG CI] "
                            + String.format(
                                    Locale.ROOT,
                                    "%" + width + "dms  %s",
                                    millis(entry.getValue()),
                                    entry.getKey()));
        }
    }

    /** Whole milliseconds, which is the resolution the printed times are stated at. */
    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    /**
     * The mod directory holding {@code path}, which is the first segment of a test path like {@code
     * buildcraft/code/x-Test.grug}.
     *
     * <p>It is the owning mod rather than whichever mod a screenshot renders, so a test in one mod
     * that screenshots another's block is still judged by its own author's fidelity promise.
     */
    @GrugGenerated("malformed test path: the engine only reports <mod>/code/<file>-Test.grug")
    private static String owningMod(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    /** Starts the next pending test, or returns false if there are none left. */
    private boolean startNextTest() {
        if (nextTestIndex >= tests.size()) {
            return false;
        }

        Map.Entry<String, Long> entry = tests.get(nextTestIndex);
        String path = entry.getKey();

        // Started before the entity exists, so the time includes the setup a test needs rather than
        // reporting only the part after it, which would make the cheapest test look free.
        currentTestStartNanos = clock.getAsLong();

        System.out.println("[GRUG CI] Executing test: " + path);

        try {
            currentEntityHandle = ops.createEntity(entry.getValue());
            if (currentEntityHandle == 0) {
                throw new RuntimeException("Failed to create an entity for the test.");
            }

            currentRunFnId = ops.getExportFnId("Test", "run");
            if (currentRunFnId == Grug.INVALID_GRUG_EXPORT_FN_ID) {
                throw new RuntimeException("Test entity missing 'run' export function.");
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            fail(path, msg != null ? msg : e.toString());
            return false;
        }

        currentTick = 0;
        // Also cleared in destroyCurrentEntity. Doing both ends means a run that was restarted or
        // aborted cannot inherit an expectation, an override or a captured origin from a previous
        // one.
        Grug.testExpectedError = null;
        Grug.testFidelityOverride = null;
        Grug.testOrigin = null;

        // Before the test's first tick, so a fixture it places at tick 0 lands inside the room. A
        // test whose fixture reaches further than the default room calls Test.set_box_radius at the
        // top of its setup, which rebuilds the room at the radius it asks for.
        boxBuilder.build(GrugTestBox.DEFAULT_RADIUS);
        return true;
    }

    /** Reports a failure, aborts the whole run, and cleans up. */
    private void fail(String path, String msg) {
        Grug.printQueue.clear();

        System.out.println("[GRUG CI] FAIL " + path);
        System.out.println("[GRUG CI] " + msg);

        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.add("FAIL " + path);
            Grug.runtimeErrorQueue.add(msg);
        }

        destroyCurrentEntity();
        finished = true;
    }

    private void destroyCurrentEntity() {
        // Cleared here rather than when the next test starts, so neither a Test.force_fidelity
        // override nor a Test.expect_error expectation can outlive the test that asked for it even
        // when that test failed partway through. A leftover expectation would keep
        // Grug.onRuntimeError recording errors for a test that is no longer running. The captured
        // origin is cleared for the same reason: the next test has to capture its own.
        Grug.testFidelityOverride = null;
        Grug.testExpectedError = null;
        Grug.testOrigin = null;
        if (currentEntityHandle != 0) {
            ops.destroyEntity(currentEntityHandle);
            currentEntityHandle = 0;
            currentRunFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
        }
    }

    /** True once the run has stopped for any reason: every test passed, or one failed. */
    public boolean isFinished() {
        return finished;
    }
}
