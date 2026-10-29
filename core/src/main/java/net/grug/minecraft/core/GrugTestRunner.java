package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugTestState;

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
 * itself over as many ticks as it needs by calling {@code ctx.not_done()}, and real frames render
 * between those invocations via the normal game loop.
 */
public class GrugTestRunner {
    /** How many ticks a single test may run for before we give up on it. */
    // A test raises its own budget from its init export, with TestInitContext.set_max_ticks.
    public static final int DEFAULT_MAX_TEST_TICKS = 200; // 10 seconds at 20 ticks/sec

    /**
     * The grug operations the runner needs, so a Java test can drive the runner's state machine
     * without the native library attached.
     */
    public interface TestEntityOps {
        long createEntity(long fileId);

        long getExportFnId(String entityType, String fnName);

        boolean callExportFn(long entityHandle, long exportFnId);

        /**
         * Calls the test's init export, which is passed the context its own calls live on. A script
         * without the export answers false, like one that aborted, so the caller tells the two
         * apart by the errors raised.
         */
        boolean callInitFn(long entityHandle, long exportFnId);

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
                    return GrugTestState.callTick(entityHandle, exportFnId);
                }

                @Override
                public boolean callInitFn(long entityHandle, long exportFnId) {
                    return GrugTestState.callInit(entityHandle, exportFnId);
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

        /** Sweeps the dropped items out of the test area, in the tick a test ends. See #323. */
        void sweepItems();

        /**
         * Whether the loader is still reloading resources, so the runner holds the next test back
         * until it is done. See #328.
         */
        default boolean loaderReloading() {
            return false;
        }

        /**
         * Whether the client still holds a block entity of an earlier test inside the area the room
         * build cleared, because it has not yet processed the packets that removed it. The runner
         * holds the test back until it is gone. See #583.
         */
        default boolean staleBlockEntitiesRemain() {
            return false;
        }

        /**
         * Closes whatever screen the client has open, once, before the run's first test starts. A
         * client that was left alone while the world and the resources loaded can have put its
         * in-game menu up over the world, and that menu pauses the world until something closes it,
         * which only a test that opens and closes a GUI otherwise does. See #655.
         */
        default void closeScreenBeforeFirstTest() {}
    }

    /**
     * Where the run's coverage is cut into the segments the coverage gate counts. A seam like
     * {@link TestEntityOps}: the unit tests drive the runner without a JaCoCo agent to cut. See
     * {@link GrugCoverage} and #626.
     */
    public interface CoverageRecorder {
        /**
         * The next test, of {@code mod}, is about to start. Called before the test creates its
         * entity, so everything the test does lands after the call.
         */
        void testStarting(String mod);

        /** Every test passed. Called before the run announces it, so a failure here is seen. */
        void runPassed();
    }

    /** The recorder of a runner with no agent to cut. */
    private static final CoverageRecorder NO_COVERAGE =
            new CoverageRecorder() {
                @Override
                public void testStarting(String mod) {}

                @Override
                public void runPassed() {}
            };

    /** The builder of a runner with no game: there is no room and no item to sweep. */
    private static final TestBoxBuilder NO_BOX =
            new TestBoxBuilder() {
                @Override
                public void build(int radius) {}

                @Override
                public void sweepItems() {}
            };

    private final TestEntityOps ops;
    private final LongSupplier clock;
    private final TestBoxBuilder boxBuilder;
    private final CoverageRecorder coverage;
    private final List<Map.Entry<String, Long>> tests;
    private final int totalCount;

    private int nextTestIndex = 0;
    private int passedCount = 0;

    /** How many times the suite runs, and how many of those are left to start. */
    private final int repeats;

    private int repeatsRemaining;

    /** 0 when no test is active, meaning "start the next one". */
    private long currentEntityHandle = 0;

    private long currentTickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private int currentTick = 0;

    /** When the running test started, so its duration can be worked out when it ends. */
    private long currentTestStartNanos = 0;

    /**
     * How long each test that finished took, in the order they ran. A path is unique because the
     * runner took it from a map's keys, so it doubles as the key here.
     */
    private final Map<String, Long> testNanos = new LinkedHashMap<>();

    private boolean finished = false;

    /** Whether the screen the client may have open has been closed for the first test yet. */
    private boolean screenClosedForRun = false;

    /**
     * A guard against a reload that will never finish, not a wait for one that will: the reloads
     * measured here take about 3 seconds, so a loader still reporting one after this long has lost
     * its finish (an apply stage that threw, or two reloads Minecraft folded into one), and holding
     * the test back any longer would only turn that into a silent run timeout. See #328.
     */
    static final long RELOAD_DEADLINE_NANOS = 60_000_000_000L;

    /**
     * When the current wait for the loader's reload began, or -1 when the runner is not waiting.
     */
    private long reloadWaitStartNanos = -1;

    /**
     * A guard against a client that never processes the removal of the last test's block entities,
     * which takes a few ticks at most, so a client still holding one after this long has lost the
     * packet and the run fails with that said rather than timing out. See #583.
     */
    static final long STALE_BLOCK_ENTITY_DEADLINE_NANOS = 30_000_000_000L;

    /**
     * When the current wait for the client to drop the last test's block entities began, or -1 when
     * the runner is not waiting. A test that is waiting has its entity, its init and its room, and
     * has only not made its first Test.tick() call yet.
     */
    private long staleWaitStartNanos = -1;

    /** Whether the current wait has been said, so a long one says so once. */
    private boolean staleWaitReported = false;

    /** How long a wait has to last before it is worth a line, since a short one is routine. */
    static final long STALE_BLOCK_ENTITY_REPORT_AFTER_NANOS = 1_000_000_000L;

    public GrugTestRunner() {
        this(
                GrugModsWatcher.fileIds,
                GrugScreenshots.validateReferenceTrees(
                        GrugCore.getAdapter().getGrugModsDirectory()),
                NATIVE_OPS,
                System::nanoTime,
                new TestBoxBuilder() {
                    @Override
                    public void build(int radius) {
                        GrugCore.getAdapter().buildTestBox(radius);
                    }

                    @Override
                    public void sweepItems() {
                        GrugCore.getAdapter().sweepTestItems();
                    }

                    @Override
                    public boolean loaderReloading() {
                        return GrugCore.getAdapter().isLoaderReloading();
                    }

                    @Override
                    public boolean staleBlockEntitiesRemain() {
                        return GrugCore.getAdapter().hasStaleTestBlockEntities();
                    }

                    @Override
                    public void closeScreenBeforeFirstTest() {
                        GrugCore.getAdapter().closeOpenScreenForTest();
                    }
                },
                repeatsFromEnv(),
                GrugCoverage.fromEnvironment());
    }

    /** Visible for tests: builds a runner over the given files without touching GrugCore. */
    public GrugTestRunner(
            Map<String, Long> fileIds, List<String> referenceErrors, TestEntityOps ops) {
        this(fileIds, referenceErrors, ops, System::nanoTime, NO_BOX, 1);
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
        this(fileIds, referenceErrors, ops, clock, NO_BOX, 1);
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
            TestBoxBuilder boxBuilder,
            int repeats) {
        this(fileIds, referenceErrors, ops, clock, boxBuilder, repeats, NO_COVERAGE);
    }

    /**
     * Visible for tests: the same, with the recorder the loaders wire to the JaCoCo agent. The
     * other constructors cut no coverage, because a unit test's agent is the build's own and a
     * reset would throw away what the unit tests reached so far.
     */
    public GrugTestRunner(
            Map<String, Long> fileIds,
            List<String> referenceErrors,
            TestEntityOps ops,
            LongSupplier clock,
            TestBoxBuilder boxBuilder,
            int repeats,
            CoverageRecorder coverage) {
        this.ops = ops;
        this.coverage = coverage;
        this.clock = clock;
        this.boxBuilder = boxBuilder;
        this.repeats = repeats;
        this.repeatsRemaining = repeats;

        // A run refuses to start on a malformed screenshots/ tree. This is the same check CI hits,
        // so an author sees every violation locally before it ever reaches a pull request.
        if (!referenceErrors.isEmpty()) {
            this.tests = new ArrayList<>();
            this.totalCount = 0;
            this.finished = true;
            synchronized (GrugErrors.runtimeErrorQueue) {
                GrugErrors.runtimeErrorQueue.add("FAIL screenshot reference directories");
                System.out.println("[GRUG CI] FAIL screenshot reference directories");
                for (String error : referenceErrors) {
                    GrugErrors.runtimeErrorQueue.add(error);
                    System.out.println("[GRUG CI] " + error);
                }
            }
            return;
        }

        this.tests = new ArrayList<>();
        // Anything already queued was reported during startup, before any test ran, so clearing it
        // here keeps the chat from showing the same message a second time. An expect_error test
        // matches testRuntimeErrors instead, which only a test that set an expectation can write
        // to and which the runner clears before each Test.tick() call, so a startup message cannot
        // answer a test.
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
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

        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.add(startMsg);
        }
    }

    /**
     * The repeat count {@code GRUG_CI_REPEATS} asks for, or 1 when it is unset.
     *
     * <p>A value that is not a positive whole number is fatal rather than rounded or ignored: the
     * run's shape is undefined otherwise, and one refused start beats a run nobody can describe.
     */
    static int parseRepeats(String value) {
        if (value == null || value.isEmpty()) return 1;
        int repeats;
        try {
            repeats = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw Grug.fatal("GRUG_CI_REPEATS is not a whole number: " + value);
        }
        if (repeats < 1) {
            throw Grug.fatal("GRUG_CI_REPEATS has to be at least 1, but is " + value);
        }
        return repeats;
    }

    private static int repeatsFromEnv() {
        return parseRepeats(System.getenv("GRUG_CI_REPEATS"));
    }

    /**
     * Does exactly one unit of work: starts the next test if none is active, then makes exactly one
     * {@code Test.tick()} call for it. Meant to be called once per real Minecraft tick.
     */
    public void tick(Object player) {
        if (finished) return;

        // Before anything else, so no test starts under a screen that is still open from before the
        // run, and the world it builds in is not paused behind one. See #655.
        if (!screenClosedForRun) {
            screenClosedForRun = true;
            boxBuilder.closeScreenBeforeFirstTest();
        }

        // A reload that a test late in a pass started is still running when the next pass starts,
        // and it keeps the client from processing what the server sent for seconds. A test that
        // begins inside it sets up a room the client has not seen and looks at a client that is
        // behind, so no test starts until the loader says the reload is over. See #328.
        if (currentEntityHandle == 0
                && nextTestIndex < tests.size()
                && boxBuilder.loaderReloading()) {
            holdForReload();
            return;
        }
        reloadWaitStartNanos = -1;

        // True when a test is starting in this very call, so its room has just been built. It stays
        // true past the check below only when that start succeeded: a failed one returns.
        boolean justStarted = currentEntityHandle == 0;
        if (justStarted && !startNextTest()) {
            // Nothing left to run.
            if (repeatsRemaining > 1) {
                // A repeat is another sample of the same suite, for a flake that passes most runs
                // and fails some. A failure above aborts the whole run already, so a repeat is a
                // detector rather than a second chance. The room is rebuilt before every test, so
                // each repeat starts every test with a fresh one; the fixtures the tests leave
                // above the room are re-placed where they were.
                repeatsRemaining--;
                nextTestIndex = 0;
                passedCount = 0;
                printTestTimes();
                System.out.println(
                        "[GRUG CI] repeat "
                                + (repeats - repeatsRemaining)
                                + " of "
                                + repeats
                                + " complete, starting the next");
                testNanos.clear();
                return;
            }
            // Marked before the write rather than after it, so that a write which throws stops the
            // loaders from queueing this runner again and repeating one failure once per tick.
            finished = true;
            if (passedCount == totalCount) {
                // Before announcing the run, because run-loader.sh reads that line as the whole run
                // succeeding and stops watching: anything that goes wrong after it is never looked
                // at, so a failed write would have left the run green with no coverage data behind
                // it. Writing here also makes the line mean what it says, which is that the tests
                // passed and their coverage was recorded. The times go first for the same reason,
                // and before the write because a write that throws is exactly when they are wanted.
                printTestTimes();
                coverage.runPassed();
                System.out.println("[GRUG CI] ALL " + totalCount + " TESTS PASSED");
            }
            return;
        }

        String path = tests.get(nextTestIndex).getKey();

        // The room is built, but the client may not have caught up with the clearing that came with
        // it. See holdForStaleBlockEntities.
        if ((justStarted || staleWaitStartNanos >= 0) && holdForStaleBlockEntities(path)) {
            return;
        }

        GrugTestState.currentTestTick = currentTick;
        GrugTestState.testNotDone = false;
        GrugTestState.currentTestMod = owningMod(path);
        // The engine clears its error flag at the start of every export function call and stops the
        // script at the first error, so the errors this call raises are the only ones that can
        // answer this call's expect_error. Anything an earlier tick left behind goes here rather
        // than standing in for an error this test never produced.
        synchronized (GrugTestState.testRuntimeErrors) {
            GrugTestState.testRuntimeErrors.clear();
        }

        boolean completed;
        try {
            completed = ops.callExportFn(currentEntityHandle, currentTickFnId);
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
            if (GrugTestState.testExpectedError != null) {
                // The script aborted because a host function errored. Accept it only if the message
                // matches what the test asked to see, so a test cannot pass on the wrong error. The
                // message comes from the record the error was written to on this thread, not from
                // the chat queue, so nothing that runs on another thread can take it first.
                String actual;
                synchronized (GrugTestState.testRuntimeErrors) {
                    actual = String.join("\n", GrugTestState.testRuntimeErrors);
                    GrugTestState.testRuntimeErrors.clear();
                }
                // The same message is also waiting for the chat, and a red message about an error
                // this test asked for is noise, so it is not shown. That is all this drain is for,
                // and clearing the chat queue cannot affect the match above. The clear holds the
                // queue's monitor, like every other access, because the client hook drains it
                // under that monitor.
                synchronized (GrugErrors.runtimeErrorQueue) {
                    GrugErrors.runtimeErrorQueue.clear();
                }
                synchronized (GrugErrors.printQueue) {
                    GrugErrors.printQueue.clear();
                }
                if (!actual.contains(GrugTestState.testExpectedError)) {
                    fail(
                            path,
                            "Expected an error containing '"
                                    + GrugTestState.testExpectedError
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
            // been logged and queued for chat by GrugErrors.onRuntimeError.
            fail(
                    path,
                    "The test script reported a runtime error and stopped early. "
                            + "See the error above for the reason.");
            return;
        }

        if (GrugTestState.testNotDone) {
            // currentTick is this invocation's tick, so asking for one more means asking for
            // currentTick + 1. Refusing that once it reaches the cap is what bounds a test to
            // the test's budget of tick() calls, which is DEFAULT_MAX_TEST_TICKS unless its init
            // raised it, on ticks 0 through the budget - 1.
            if (currentTick + 1 >= GrugTestState.maxTestTicks) {
                // Deliberately loud: a test that keeps asking for more ticks should hang for the
                // whole budget and then say so, rather than quietly passing or running forever.
                fail(
                        path,
                        "Test exceeded "
                                + GrugTestState.maxTestTicks
                                + " ticks without finishing (did it forget to stop calling"
                                + " ctx.not_done()?)");
                return;
            }
            currentTick++;
            return;
        }

        // The test stopped asking for ticks, so it's done.
        if (GrugTestState.testExpectedError != null) {
            fail(
                    path,
                    "Test expected an error containing '"
                            + GrugTestState.testExpectedError
                            + "', but it completed without one.");
            return;
        }
        System.out.println("[GRUG CI] PASS " + path);

        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.add("\u00A7aPASS " + path);
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
     * that screenshots another's block is still judged by its own author's claim of being exact.
     */
    @GrugGenerated("malformed test path: the engine only reports <mod>/code/<file>-Test.grug")
    private static String owningMod(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    /**
     * Holds the next test back for the loader's reload, saying so once per wait, and fails the run
     * if the reload outlasts {@link #RELOAD_DEADLINE_NANOS}.
     */
    private void holdForReload() {
        String path = tests.get(nextTestIndex).getKey();
        long now = clock.getAsLong();
        if (reloadWaitStartNanos < 0) {
            reloadWaitStartNanos = now;
            System.out.println(
                    "[GRUG CI] Waiting for the loader's resource reload to finish before starting "
                            + path);
        } else if (now - reloadWaitStartNanos > RELOAD_DEADLINE_NANOS) {
            fail(
                    path,
                    "The loader's resource reload never finished: it was still reported after "
                            + (RELOAD_DEADLINE_NANOS / 1_000_000_000L)
                            + " seconds, so this test was not started.");
        }
    }

    /**
     * Holds the test back, after its room is built and before its first tick, while the client
     * still holds a block entity the room build cleared, and answers whether it did.
     *
     * <p>The room build clears the area on the server, and the client learns of it from packets
     * that reach it a tick or more later, while the test's first calls go straight to the client
     * thread. A rebuild of a section the client still has the old block entities in draws them on a
     * chunk worker thread, and that draw enters the VM in the middle of whichever call the test has
     * suspended on the client thread. The engine's error flag is not per call, so a draw that
     * reports an err stops the test's own call, and the run fails a test for an err that is an
     * earlier test's. Waiting until the client has dropped them means a rebuild can no longer draw
     * them. See #583.
     */
    private boolean holdForStaleBlockEntities(String path) {
        if (!boxBuilder.staleBlockEntitiesRemain()) {
            staleWaitStartNanos = -1;
            staleWaitReported = false;
            return false;
        }
        long now = clock.getAsLong();
        if (staleWaitStartNanos < 0) {
            staleWaitStartNanos = now;
        } else if (now - staleWaitStartNanos > STALE_BLOCK_ENTITY_DEADLINE_NANOS) {
            staleWaitStartNanos = -1;
            staleWaitReported = false;
            fail(
                    path,
                    "The client still held a block entity of an earlier test after "
                            + (STALE_BLOCK_ENTITY_DEADLINE_NANOS / 1_000_000_000L)
                            + " seconds, so this test was not started.");
        } else if (!staleWaitReported
                && now - staleWaitStartNanos > STALE_BLOCK_ENTITY_REPORT_AFTER_NANOS) {
            // Not on the first tick, which every test takes: a loader may need one tick to find
            // out.
            staleWaitReported = true;
            System.out.println(
                    "[GRUG CI] Waiting for the client to drop the last test's block entities"
                            + " before the first tick of "
                            + path);
        }
        return true;
    }

    /** Starts the next pending test, or returns false if there are none left. */
    private boolean startNextTest() {
        if (nextTestIndex >= tests.size()) {
            return false;
        }

        Map.Entry<String, Long> entry = tests.get(nextTestIndex);
        String path = entry.getKey();

        // Before anything the test does, so the test's entity and init land in its mod's segment of
        // the coverage. The tests are sorted by path, so the mods follow one another in blocks and
        // this cuts only where the mod changes. See #626.
        coverage.testStarting(owningMod(path));

        // Started before the entity exists, so the time includes the setup a test needs rather than
        // reporting only the part after it, which would make the cheapest test look free.
        currentTestStartNanos = clock.getAsLong();

        System.out.println("[GRUG CI] Executing test: " + path);

        try {
            currentEntityHandle = ops.createEntity(entry.getValue());
            if (currentEntityHandle == 0) {
                throw new RuntimeException("Failed to create an entity for the test.");
            }

            currentTickFnId = ops.getExportFnId("Test", "tick");
            if (currentTickFnId == Grug.INVALID_GRUG_EXPORT_FN_ID) {
                throw new RuntimeException("Test entity missing 'tick' export function.");
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
        GrugTestState.testExpectedError = null;
        GrugTestState.testOrigin = null;
        GrugTestState.maxTestTicks = DEFAULT_MAX_TEST_TICKS;

        // Before the first tick, so the budget it sets is the one the whole test runs under.
        long initFnId = ops.getExportFnId("Test", "init");
        if (initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            int errorsBefore = GrugTestState.runtimeErrorCount;
            ops.callInitFn(currentEntityHandle, initFnId);
            if (GrugTestState.runtimeErrorCount != errorsBefore) {
                fail(
                        path,
                        "The test's init reported a runtime error and stopped early. "
                                + "See the error above for the reason.");
                return false;
            }
        }

        // Before the test's first tick, so a fixture it places at tick 0 lands inside the room. A
        // test whose fixture reaches further than the default room calls Test.set_box_radius at the
        // top of its setup, which rebuilds the room at the radius it asks for.
        boxBuilder.build(GrugTestBox.DEFAULT_RADIUS);
        return true;
    }

    /** Reports a failure, aborts the whole run, and cleans up. */
    private void fail(String path, String msg) {
        // The client hook drains this queue under its monitor, so the pending messages are read
        // and cleared under the same monitor: a concurrent poll could otherwise invalidate the
        // iteration, and a message queued in the tick the test failed in would be lost.
        synchronized (GrugErrors.printQueue) {
            for (String line : GrugErrors.printQueue) {
                System.out.println("[GRUG CI] pending: " + line);
            }
            GrugErrors.printQueue.clear();
        }

        System.out.println("[GRUG CI] FAIL " + path);
        System.out.println("[GRUG CI] " + msg);

        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.add("FAIL " + path);
            GrugErrors.runtimeErrorQueue.add(msg);
        }

        destroyCurrentEntity();
        finished = true;
    }

    private void destroyCurrentEntity() {
        // Cleared here rather than when the next test starts, so a ctx.expect_error expectation
        // cannot outlive the test that asked for it even when that test failed partway through. A
        // leftover expectation would keep
        // GrugErrors.onRuntimeError recording errors for a test that is no longer running. The
        // captured origin is cleared for the same reason: the next test has to capture its own.
        GrugTestState.testExpectedError = null;
        GrugTestState.testOrigin = null;
        GrugTestState.maxTestTicks = DEFAULT_MAX_TEST_TICKS;
        if (currentEntityHandle != 0) {
            // In the tick the test ends, whether it passed, expected its error or failed, so the
            // last items it dropped are gone before any gap can let the player pick them up. The
            // room build sweeps again when the next test starts, because clearing the area drops
            // items of its own. See #323.
            boxBuilder.sweepItems();
            ops.destroyEntity(currentEntityHandle);
            currentEntityHandle = 0;
            currentTickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
        }
        // Every test ending passes through here, whether it passed, failed, or aborted on a host
        // function error, so the camera a test moved is put back without the test having to ask.
        // A test that never called Test.setup_graphics_camera never moved it and the adapter's
        // restore is a no-op for it. A test that fails partway through no longer leaves the player
        // moved for the next one. See #393 and #408.
        GrugCore.getAdapter().restoreCameraAfterGraphicsTest();
    }

    /** True once the run has stopped for any reason: every test passed, or one failed. */
    public boolean isFinished() {
        return finished;
    }
}
