package net.grug.minecraft.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugTestState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    /**
     * How many ticks a single test may run for before we give up on it, counted in the ticks of a
     * real-time clock: the runner multiplies it by the speed the game's clock runs at, so it stays
     * the same length of time. A test raises its own budget from its init export, with
     * TestInitContext.set_max_ticks.
     */
    public static final int DEFAULT_MAX_TEST_TICKS = 200; // 10 seconds at 20 ticks/sec

    /**
     * The upper bound {@code GRUG_CI_MAX_GAME_SPEED} accepts. Every loader runs at most 10 ticks
     * for one rendered frame, so a speed beyond about 10 times the frame rate gains nothing; what a
     * loader runs at is {@link ModLoaderAdapter#maxGameSpeed()}, and this only bounds the cap.
     */
    public static final int MAX_GAME_SPEED = 100;

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
                    return GrugEntities.createTestEntity(fileId);
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
        /**
         * Puts the player where a test starts: the centre of their own block, with a level view.
         * Called before {@link #build}, so the room is built around the place the test runs in, and
         * before the test's first {@code Test.get_origin} answer is captured. A no-op on a side
         * with no local player. See #609.
         */
        default void snapPlayer() {}

        /**
         * Empties the player's inventory and selects the first hotbar slot, before every test
         * starts, so no item an earlier test or an earlier pass of a repeated run left in a hotbar
         * slot reaches a capture. See #930.
         */
        default void resetPlayerInventory() {}

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

        /**
         * The fastest the loader's clock usefully runs, as a multiple of real time, which every
         * test runs at. 1 means the loader cannot run its clock faster. See {@link
         * ModLoaderAdapter#maxGameSpeed}.
         */
        default double maxGameSpeed() {
            return 1;
        }

        /**
         * Sets how fast the game's clock runs and answers whether the loader did, which the runner
         * asks for at the start of every test and, with 1, to put real time back when it ends. A
         * loader that cannot keeps the default. See {@link ModLoaderAdapter#setGameSpeed}.
         */
        default boolean setGameSpeed(double speed) {
            return false;
        }
    }

    /**
     * Where the run's coverage is cut into the segments the coverage gate counts. A seam like
     * {@link TestEntityOps}: the unit tests drive the runner without a JaCoCo agent to cut. See
     * {@link GrugCoverage} and #626.
     */
    public interface CoverageRecorder {
        /**
         * The next test, whose path is {@code testPath}, is about to start. Called before the test
         * creates its entity, so everything the test does lands after the call. The recorder cuts
         * the segment the previous test (or the boot) just ended here, so each test is its own
         * segment. See #786.
         */
        void testStarting(String testPath);

        /** Every test passed. Called before the run announces it, so a failure here is seen. */
        void runPassed();
    }

    /** The recorder of a runner with no agent to cut. */
    private static final CoverageRecorder NO_COVERAGE =
            new CoverageRecorder() {
                @Override
                public void testStarting(String testPath) {}

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

    /** The fastest game speed a test may run at; 1 keeps every test in real time. */
    private final int maxGameSpeed;

    /**
     * The multiple of real time the loader's clock runs at for the running test, which is 1 when
     * the test runs in real time. The end of the test puts real time back only when it is above 1,
     * and the test's tick budget is scaled by it. See {@link #tickBudget}.
     */
    private double appliedGameSpeed = 1;

    /**
     * What the loader answered when the last test asked for a faster clock, so the log says it when
     * it changes and not for every test. Starts as the answer the first test is not told.
     */
    private Boolean lastGameSpeedAnswer = null;

    private int nextTestIndex = 0;
    private int passedCount = 0;

    /** How many times the suite runs, and how many of those are left to start. */
    private final int repeats;

    private int repeatsRemaining;

    /** 0 when no test is active, meaning "start the next one". */
    private long currentEntityHandle = 0;

    private long currentTickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private int currentTick = 0;

    /**
     * When the running test started, so its duration can be worked out when it ends, and -1 until a
     * test has started, so a test that never did is not timed as if it took the whole run.
     */
    private long currentTestStartNanos = -1;

    /**
     * How many tests this runner has started, raised once a test's room is built and before its
     * first tick.
     *
     * <p>On 1.20.6 the runner is ticked by a task the client queues on the integrated server's
     * thread, and the room build keeps that thread busy for far longer than one frame while the
     * client goes on queueing a tick for every tick of its own. Those ticks would run back to back
     * as the new test's first ticks, with no world tick and no frame between them, so a loader that
     * queues them stamps each with this count at queue time and drops the ones a later start has
     * overtaken: then a test's first tick is its first tick. See #717.
     */
    private volatile long startedTests = 0;

    /**
     * How many tests this runner has started, which a loader that queues runner ticks on another
     * thread stamps them with. See {@link #startedTests}.
     */
    public long startedTests() {
        return startedTests;
    }

    /**
     * Whether a runner tick a loader queued is for a test that has since ended, and so must be
     * dropped rather than run as the next test's tick.
     *
     * <p>A loader that queues runner ticks on another thread reads {@link #startedTests()} when it
     * queues one and asks this when the task runs. Equal counts mean no test has started in
     * between, so the tick still belongs to the test it was queued for. A count that moved means a
     * later test started, which builds its room on that thread and leaves a queue of ticks meant
     * for the test that ended; those must not become the new test's first ticks. The decision is a
     * pure function of the two counts so it is unit-tested for both, rather than left to a loader's
     * game code (see #717 and {@code GrugTestRunnerTest}).
     */
    public static boolean queuedTickIsStale(long startsWhenQueued, long startsNow) {
        return startsNow != startsWhenQueued;
    }

    /**
     * How long each test that finished took, in the order they ran. A path is unique because the
     * runner took it from a map's keys, so it doubles as the key here.
     */
    private final Map<String, Long> testNanos = new LinkedHashMap<>();

    /**
     * The record the run read to order its tests, or null when it read none: a run with no record
     * file, or one whose record could not be read and fell back to name order.
     */
    private GrugTestOrder.Record record = null;

    /**
     * Where the run writes the record it hands to the next one, or null when there is nowhere: a
     * run without a file of its own, and always a reference run, which loads the harness rather
     * than the port and would hand back a record of the harness's tests.
     */
    private Path testTimesFile = null;

    /**
     * Every test the loader has, the selected and the not, which is what the record the run writes
     * back is a record of: each keeps a time, whether this run measured it or the record it read
     * held one.
     */
    private final List<String> loadedTests = new ArrayList<>();

    /** Writes the test order record with the shape the runner reads it back in. */
    private static final Gson GSON = new Gson();

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
                    public void snapPlayer() {
                        GrugCore.getAdapter().snapPlayerForTest();
                    }

                    @Override
                    public void resetPlayerInventory() {
                        GrugCore.getAdapter().resetPlayerInventoryForTest();
                    }

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

                    @Override
                    public double maxGameSpeed() {
                        return GrugCore.getAdapter().maxGameSpeed();
                    }

                    @Override
                    public boolean setGameSpeed(double speed) {
                        return GrugCore.getAdapter().setGameSpeed(speed);
                    }
                },
                repeatsFromEnv(),
                GrugCoverage.fromEnvironment(),
                modsFromEnv(),
                maxGameSpeedFromEnv(),
                carryOverCoverageTestsFromEnv(),
                testsFromEnv());
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
        this(
                fileIds,
                referenceErrors,
                ops,
                clock,
                boxBuilder,
                repeats,
                coverage,
                Collections.emptySet());
    }

    /**
     * Visible for tests: the same, running only the tests of {@code selectedMods}, where an empty
     * set selects every mod. See {@link #parseMods}. No coverage test is carried over.
     */
    public GrugTestRunner(
            Map<String, Long> fileIds,
            List<String> referenceErrors,
            TestEntityOps ops,
            LongSupplier clock,
            TestBoxBuilder boxBuilder,
            int repeats,
            CoverageRecorder coverage,
            Set<String> selectedMods) {
        this(
                fileIds,
                referenceErrors,
                ops,
                clock,
                boxBuilder,
                repeats,
                coverage,
                selectedMods,
                MAX_GAME_SPEED,
                Collections.emptySet());
    }

    /**
     * Visible for tests: the same, with the fastest game speed a test may run at, which is what
     * {@code GRUG_CI_MAX_GAME_SPEED} sets, and with the coverage tests to carry over, which is what
     * {@code GRUG_CI_COVERAGE_CARRY_OVER} sets. See {@link #parseMaxGameSpeed} and {@link
     * #parseCarryOverCoverageTests}.
     */
    public GrugTestRunner(
            Map<String, Long> fileIds,
            List<String> referenceErrors,
            TestEntityOps ops,
            LongSupplier clock,
            TestBoxBuilder boxBuilder,
            int repeats,
            CoverageRecorder coverage,
            Set<String> selectedMods,
            int maxGameSpeed,
            Set<String> carryOverCoverageTests) {
        this(
                fileIds,
                referenceErrors,
                ops,
                clock,
                boxBuilder,
                repeats,
                coverage,
                selectedMods,
                maxGameSpeed,
                carryOverCoverageTests,
                Collections.emptySet());
    }

    /**
     * Visible for tests: the same, running only the tests of {@code selectedTests}, where an empty
     * set selects every test. See {@link #parseTests}.
     */
    public GrugTestRunner(
            Map<String, Long> fileIds,
            List<String> referenceErrors,
            TestEntityOps ops,
            LongSupplier clock,
            TestBoxBuilder boxBuilder,
            int repeats,
            CoverageRecorder coverage,
            Set<String> selectedMods,
            int maxGameSpeed,
            Set<String> carryOverCoverageTests,
            Set<String> selectedTests) {
        this.maxGameSpeed = maxGameSpeed;
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
        checkSelectedModsExist(selectedMods, fileIds);
        checkCarryOverTestsExist(carryOverCoverageTests, fileIds);
        checkSelectedTestsExist(selectedTests, fileIds);
        for (Map.Entry<String, Long> entry : fileIds.entrySet()) {
            if (entry.getKey().endsWith(GrugFileIndex.TEST_SUFFIX)
                    && (selectedMods.isEmpty() || selectedMods.contains(owningMod(entry.getKey())))
                    && (selectedTests.isEmpty() || selectedTests.contains(entry.getKey()))
                    && (carryOverCoverageTests.isEmpty()
                            || !carryOverCoverageTests.contains(entry.getKey()))) {
                tests.add(entry);
            }
        }
        // The default order is the name order, which is stable for the reason a selection never
        // reorders it: a test that runs must meet the world state it meets in the full run. The
        // record read below may reorder the list, failed first and then slowest first, because the
        // room build clears the area before every test, so a test meets its own fixture and not
        // what the suite left it (#262).
        tests.sort(Map.Entry.comparingByKey());
        this.totalCount = tests.size();

        String startMsg =
                "Running " + totalCount + " " + (totalCount == 1 ? "test" : "tests") + "...";
        if (!selectedMods.isEmpty()) {
            System.out.println(
                    "[GRUG CI] Only the tests of these mods run: "
                            + String.join(", ", selectedMods));
        }
        if (!selectedTests.isEmpty()) {
            System.out.println(
                    "[GRUG CI] Only these tests run: " + String.join(", ", selectedTests));
        }
        if (!carryOverCoverageTests.isEmpty()) {
            // The guarantee the carry-over loses (#786), said in the log the run is read in: a
            // skipped test's assertions do not rerun, so the run's proof rests on the recorded
            // coverage of the tests it skipped.
            System.out.println(
                    "[GRUG CI] Carrying over "
                            + carryOverCoverageTests.size()
                            + " coverage test(s) from the base commit's recorded coverage, whose"
                            + " assertions do not rerun (#786): "
                            + String.join(", ", carryOverCoverageTests));
        }
        System.out.println("[GRUG CI] " + startMsg);

        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.add(startMsg);
        }

        this.testTimesFile = testTimesFile();
        if (testTimesFile != null) {
            for (String path : fileIds.keySet()) {
                if (path.endsWith(GrugFileIndex.TEST_SUFFIX)) {
                    loadedTests.add(path);
                }
            }
            try {
                record =
                        GrugTestOrder.parseRecord(
                                new String(
                                        Files.readAllBytes(testTimesFile), StandardCharsets.UTF_8));
                orderTestsByRecord();
            } catch (Exception e) {
                // The record is data, not an invariant: a broken one costs this run its order and
                // nothing more, and the line says so where the run is read.
                System.out.println(
                        "[GRUG CI] The test order record at "
                                + testTimesFile
                                + " could not be read ("
                                + e
                                + "), so the tests run in name order.");
            }
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
     * The mods whose tests {@code GRUG_CI_MODS} asks for: a comma separated list of mod directory
     * names, in the order given with duplicates dropped. Unset, empty or only whitespace selects
     * every mod, which is returned as the empty set.
     *
     * <p>Every mod is still loaded whatever is selected, so a clash in the shared {@code grug:}
     * namespace still shows at boot; only the tests of the others are skipped.
     *
     * <p>A list that is not empty but has an empty entry, such as {@code coverage,} or {@code
     * a,,b}, is fatal: it is what a shell variable that expanded to nothing leaves behind, and
     * running fewer tests than the author meant while reporting green is the one outcome to avoid.
     */
    static Set<String> parseMods(String value) {
        Set<String> mods = new LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) return mods;
        for (String name : value.split(",", -1)) {
            String mod = name.trim();
            if (mod.isEmpty()) {
                throw Grug.fatal(
                        "GRUG_CI_MODS has an empty entry, but each has to name a mod: '"
                                + value
                                + "'");
            }
            mods.add(mod);
        }
        return mods;
    }

    private static Set<String> modsFromEnv() {
        return parseMods(System.getenv("GRUG_CI_MODS"));
    }

    /**
     * The coverage tests {@code GRUG_CI_COVERAGE_CARRY_OVER} carries over instead of runs (#786): a
     * comma separated list of runner paths of coverage tests, in the order given with duplicates
     * dropped. Unset, empty or only whitespace carries nothing over, which is returned as the empty
     * set and runs every coverage test.
     *
     * <p>Each carried-over test is not rerun at all: the coverage gate counts its recorded coverage
     * from the base commit's run in its place, which is the guarantee the carry-over loses. That is
     * why the proof that a test may be carried over is conservative, and a doubt runs the test
     * rather than carrying it over.
     *
     * <p>A non-empty list with an empty entry, such as {@code coverage/tests/a-Test.grug,}, is
     * fatal: it is what a shell variable that expanded to nothing leaves behind, and running fewer
     * tests than the author meant while reporting green is the one outcome to avoid.
     */
    static Set<String> parseCarryOverCoverageTests(String value) {
        Set<String> tests = new LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) return tests;
        for (String path : value.split(",", -1)) {
            String test = path.trim();
            if (test.isEmpty()) {
                throw Grug.fatal(
                        "GRUG_CI_COVERAGE_CARRY_OVER has an empty entry, but each has to name a"
                                + " coverage test: '"
                                + value
                                + "'");
            }
            tests.add(test);
        }
        return tests;
    }

    /**
     * The tests {@code GRUG_CI_TESTS} asks for: a comma separated list of runner paths of tests
     * (such as {@code coverage/tests/block_pos-Test.grug}), in the order given with duplicates
     * dropped. Unset, empty or only whitespace selects every test, which is returned as the empty
     * set.
     *
     * <p>It is how a run that cannot execute every test picks the ones it can: a dedicated server
     * has no screen to capture, so it names the tests that need none. It stands in until a test
     * declares for itself whether it can run on a dedicated server (#168), and it combines with
     * {@code GRUG_CI_MODS} by intersection.
     *
     * <p>A non-empty list with an empty entry is fatal, for the same reason as {@link #parseMods}:
     * running fewer tests than the author meant while reporting green is the one outcome to avoid.
     */
    static Set<String> parseTests(String value) {
        Set<String> tests = new LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) return tests;
        for (String path : value.split(",", -1)) {
            String test = path.trim();
            if (test.isEmpty()) {
                throw Grug.fatal(
                        "GRUG_CI_TESTS has an empty entry, but each has to name a test: '"
                                + value
                                + "'");
            }
            tests.add(test);
        }
        return tests;
    }

    private static Set<String> testsFromEnv() {
        return parseTests(System.getenv("GRUG_CI_TESTS"));
    }

    /**
     * Fails when a selected test is not among the tests that were loaded, because the run would
     * otherwise pass having run less than it was asked to.
     */
    private static void checkSelectedTestsExist(
            Set<String> selectedTests, Map<String, Long> fileIds) {
        if (selectedTests.isEmpty()) return;
        Set<String> loaded = new TreeSet<>();
        for (String path : fileIds.keySet()) {
            if (path.endsWith(GrugFileIndex.TEST_SUFFIX)) {
                loaded.add(path);
            }
        }
        for (String test : selectedTests) {
            if (!loaded.contains(test)) {
                throw Grug.fatal(
                        "GRUG_CI_TESTS names the test '"
                                + test
                                + "', but the loaded tests are: "
                                + String.join(", ", loaded));
            }
        }
    }

    private static Set<String> carryOverCoverageTestsFromEnv() {
        return parseCarryOverCoverageTests(System.getenv("GRUG_CI_COVERAGE_CARRY_OVER"));
    }

    /**
     * The fastest game speed {@code GRUG_CI_MAX_GAME_SPEED} allows a test, or {@link
     * #MAX_GAME_SPEED} when it is unset or empty. {@code 1} keeps every test in real time, which is
     * how a run is compared with and without the speed-up, or tells a flake from the clock.
     *
     * <p>A value that is not a whole number from 1 to {@link #MAX_GAME_SPEED} is fatal rather than
     * clamped, for the same reason as {@link #parseRepeats}: a run that quietly used another speed
     * than the one asked for cannot be described.
     */
    static int parseMaxGameSpeed(String value) {
        if (value == null || value.isEmpty()) return MAX_GAME_SPEED;
        int speed;
        try {
            speed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw Grug.fatal("GRUG_CI_MAX_GAME_SPEED is not a whole number: " + value);
        }
        if (speed < 1 || speed > MAX_GAME_SPEED) {
            throw Grug.fatal(
                    "GRUG_CI_MAX_GAME_SPEED has to be a whole number from 1 to "
                            + MAX_GAME_SPEED
                            + ", but is "
                            + value);
        }
        return speed;
    }

    private static int maxGameSpeedFromEnv() {
        return parseMaxGameSpeed(System.getenv("GRUG_CI_MAX_GAME_SPEED"));
    }

    /**
     * Fails when a selected mod is not among the mods that were loaded, because the run would
     * otherwise pass having run nothing for it. A mod is known by the first path segment of any
     * file it holds, a test or not, so a mod without tests can be selected and runs none.
     */
    private static void checkSelectedModsExist(
            Set<String> selectedMods, Map<String, Long> fileIds) {
        if (selectedMods.isEmpty()) return;
        Set<String> loaded = new TreeSet<>();
        for (String path : fileIds.keySet()) {
            loaded.add(owningMod(path));
        }
        for (String mod : selectedMods) {
            if (!loaded.contains(mod)) {
                throw Grug.fatal(
                        "GRUG_CI_MODS names the mod '"
                                + mod
                                + "', but the mods loaded are: "
                                + String.join(", ", loaded));
            }
        }
    }

    /**
     * Fails when a carried-over coverage test is not among the coverage tests that were loaded,
     * because the run would then carry over a test that does not exist, or run a test it was meant
     * to skip. See {@link #parseCarryOverCoverageTests}.
     */
    private static void checkCarryOverTestsExist(
            Set<String> carryOverCoverageTests, Map<String, Long> fileIds) {
        if (carryOverCoverageTests.isEmpty()) return;
        Set<String> loaded = new TreeSet<>();
        for (String path : fileIds.keySet()) {
            if (path.startsWith(GrugCoverage.COUNTED_MOD + "/")
                    && path.endsWith(GrugFileIndex.TEST_SUFFIX)) {
                loaded.add(path);
            }
        }
        for (String test : carryOverCoverageTests) {
            if (!loaded.contains(test)) {
                throw Grug.fatal(
                        "GRUG_CI_COVERAGE_CARRY_OVER names the coverage test '"
                                + test
                                + "', but the loaded coverage tests are: "
                                + String.join(", ", loaded));
            }
        }
    }

    /**
     * Does exactly one unit of work: starts the next test if none is active, then makes exactly one
     * {@code Test.tick()} call for it. Meant to be called once per real Minecraft tick.
     */
    public void tick(Object player) {
        if (finished) return;

        // A Java exception a host function threw can arrive with no call of the test's own to
        // abort, from a render pass, the room build or a fixture the test placed. Left to the log
        // it let the run end green with no test named, so it fails the run here, under the test
        // that owns it (#890).
        if (failOnHostFunctionException()) return;

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
                writeTestTimesRecord(Collections.emptySet());
                coverage.runPassed();
                System.out.println("[GRUG CI] ALL " + totalCount + " TESTS PASSED");
            }
            return;
        }

        // The room build runs game code that can reach a host function, so it can throw before the
        // test's first tick. It belongs to the test that is starting.
        if (justStarted && failOnHostFunctionException()) return;

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
                // A host function exception the test asked to see is accounted for here, or it
                // would fail the next tick as a leftover. It is reported like any other host
                // function error, which is what expect_error matches.
                GrugErrors.takeHostFunctionException();
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
            long budget = tickBudget();
            if (currentTick + 1 >= budget) {
                // Deliberately loud: a test that keeps asking for more ticks should hang for the
                // whole budget and then say so, rather than quietly passing or running forever.
                fail(
                        path,
                        "Test exceeded "
                                + budget
                                + " ticks"
                                + budgetExplanation()
                                + " without finishing (did it forget to stop calling"
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
     * How many of the runner's ticks the running test may take: its budget of ticks (200, or what
     * its init raised it to) times the speed the game's clock runs at.
     *
     * <p>The budget guards a wait for work that takes real time, such as a packet from the server,
     * a file watcher or a resource reload, so it is a budget of time that happens to be counted in
     * the ticks of a real-time clock. A clock that runs N times faster ticks N times as often in
     * the same seconds, so the same wait needs N times as many ticks, and a budget left unscaled
     * would shrink to a Nth of the time the test was sized for.
     */
    private long tickBudget() {
        return Math.round(GrugTestState.maxTestTicks * appliedGameSpeed);
    }

    /** Says how the budget came about when the clock was sped up, and nothing otherwise. */
    private String budgetExplanation() {
        if (appliedGameSpeed <= 1) return "";
        return " ("
                + GrugTestState.maxTestTicks
                + " ticks of real time, at game speed "
                + format(appliedGameSpeed)
                + "x)";
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
        if (currentTestStartNanos < 0) {
            // The test never started, so it took no time, and must not be timed from the start of
            // whatever test it did not replace.
            return;
        }
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

    /** The environment variable that points a run at its test order record. */
    static final String TEST_TIMES_FILE_ENV_VAR = "GRUG_CI_TEST_TIMES_FILE";

    /** The property equivalent, so a test can point the runner at a record of its own. */
    static final String TEST_TIMES_FILE_PROPERTY = "grug.test.times.file";

    /**
     * Where the run reads and writes its test order record, or null when there is nowhere: no
     * variable or property for the run, and always a reference run, which loads the harness rather
     * than the port, so its record would be of the harness's tests and drop every one of the
     * port's.
     */
    static Path testTimesFile() {
        if (GrugReference.isReferenceRun()) {
            return null;
        }
        String value =
                System.getProperty(
                        TEST_TIMES_FILE_PROPERTY, System.getenv(TEST_TIMES_FILE_ENV_VAR));
        if (value == null || value.isEmpty()) {
            return null;
        }
        return Paths.get(value);
    }

    /**
     * Runs the tests in the order the record says: the ones it names as failed first, then the
     * rest, both slowest first, and says so in the log and in the chat, so a run's order is
     * explained where the run is read. An empty record holds nothing to order by, so the run keeps
     * the name order it found and does not claim the record for it.
     */
    private void orderTestsByRecord() {
        if (record.failed.isEmpty() && record.times.isEmpty()) {
            return;
        }
        List<String> current = new ArrayList<>(tests.size());
        for (Map.Entry<String, Long> entry : tests) {
            current.add(entry.getKey());
        }
        List<String> order = GrugTestOrder.orderTests(record.times, record.failed, current);
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }
        tests.sort(Comparator.comparingInt(entry -> position.get(entry.getKey())));

        int failedNow = 0;
        for (String test : record.failed) {
            if (position.containsKey(test)) {
                failedNow++;
            }
        }
        String orderMsg;
        if (failedNow == 0) {
            orderMsg = "Test order: slowest first, from " + testTimesFile;
        } else {
            orderMsg =
                    "Test order: "
                            + failedNow
                            + " previously failed "
                            + (failedNow == 1 ? "test" : "tests")
                            + " first, then slowest first, from "
                            + testTimesFile;
        }
        System.out.println("[GRUG CI] " + orderMsg);
        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.add(orderMsg);
        }
    }

    /**
     * The times the record the run writes back holds: this run's measurement for a test it ran, and
     * the record it read for a test it did not reach, so a run of a subset of the mods never throws
     * the other mods' times away. A test the loader no longer has is in neither, so it is dropped.
     */
    private Map<String, Long> mergedTimes() {
        Map<String, Long> times = new LinkedHashMap<>();
        for (String test : loadedTests) {
            Long fresh = testNanos.get(test);
            if (fresh != null) {
                times.put(test, fresh);
            } else if (record != null) {
                Long old = record.times.get(test);
                if (old != null) {
                    times.put(test, old);
                }
            }
        }
        return times;
    }

    /**
     * Writes the record the run hands to the next one: the times it measured, and the test it
     * failed, if one did. Written when the run ends, on a pass and on a failure alike, and never on
     * a run the game never let finish, so a timeout or a crash leaves the record it found.
     */
    private void writeTestTimesRecord(Set<String> failed) {
        if (testTimesFile == null) {
            return;
        }
        JsonObject json = new JsonObject();
        JsonArray failedArray = new JsonArray();
        for (String test : failed) {
            failedArray.add(test);
        }
        json.add("failed", failedArray);
        JsonObject times = new JsonObject();
        for (Map.Entry<String, Long> entry : mergedTimes().entrySet()) {
            times.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("times", times);
        try {
            Files.write(testTimesFile, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println(
                    "[GRUG CI] The updated test order record could not be written to "
                            + testTimesFile
                            + ": "
                            + e);
        }
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

        // Before anything the test does, so the test's entity and init land in its own segment of
        // the coverage: the previous test's (or the boot's) segment is cut here, one segment per
        // test, so a later run can carry over the coverage of the tests it does not rerun (#786).
        coverage.testStarting(path);

        // Started before the entity exists, so the time includes the setup a test needs rather than
        // reporting only the part after it, which would make the cheapest test look free.
        currentTestStartNanos = clock.getAsLong();

        System.out.println("[GRUG CI] Executing test: " + path);

        // Before the entity exists, so the test's member scope starts with a root list of its own
        // and nothing an earlier test created is still rooted while this one runs. A test's
        // entities then last until it ends. See #883.
        GrugEntities.beginTestEntities();

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

        // Before the init, the room and the first get_origin answer, so the player is already at
        // the centre of their block with a level view when any of them looks. See #609.
        boxBuilder.snapPlayer();
        boxBuilder.resetPlayerInventory();

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

        // After the room, whose frames are drawn, so the client draws nothing more until this test
        // asks for a frame with a capture or a settle wait. A test that never asks runs on its
        // ticks alone. See GrugFrameRequest and #760.
        GrugFrameRequest.beginTest();

        // After the room, so the setup that every test shares runs in real time, and before the
        // first tick, so the whole wait the test asked to speed up is sped up.
        applyGameSpeed(path);

        // The room is built and the clock is the test's, and its first tick is the one that comes
        // next: raised here, so a loader that stamped the runner ticks it queued while the room was
        // built drops them rather than running them as this test's first ticks. See #717.
        startedTests++;
        return true;
    }

    /**
     * Runs the game's clock at the fastest speed the loader runs at, unless {@code
     * GRUG_CI_MAX_GAME_SPEED} lowers it, and says so in the log. Every test runs this way: there is
     * no opt-in and no opt-out, because a test waits for a condition and so is right at any speed,
     * and one that was not has to be fixed.
     *
     * <p>A loader that cannot speed up its clock reports 1 and the test runs at 1x without a line,
     * since that is how the game runs anyway. A clock capped to real time by {@code
     * GRUG_CI_MAX_GAME_SPEED} is likewise left alone.
     */
    private void applyGameSpeed(String path) {
        double speed = Math.min(boxBuilder.maxGameSpeed(), maxGameSpeed);
        if (speed <= 1) return;
        boolean applied = boxBuilder.setGameSpeed(speed);
        if (applied) appliedGameSpeed = speed;
        // Said once for the run, since every test gets the same answer, and again for a test that
        // gets a different one.
        if (!Boolean.valueOf(applied).equals(lastGameSpeedAnswer)) {
            lastGameSpeedAnswer = applied;
            System.out.println(
                    applied
                            ? "[GRUG CI] Game speed " + format(speed) + "x from " + path
                            : "[GRUG CI] "
                                    + path
                                    + " could not be run at game speed "
                                    + format(speed)
                                    + "x, so it runs at 1x");
        }
    }

    /** Puts the game's clock back to real time if this test sped it up. */
    private void restoreGameSpeed() {
        if (appliedGameSpeed <= 1) return;
        // Cleared first, so a loader that throws while restoring is not asked again by the cleanup
        // of the failure that follows.
        appliedGameSpeed = 1;
        boxBuilder.setGameSpeed(1);
    }

    /** A whole number without its decimal point, which is what every speed that gets here is. */
    private static String format(double speed) {
        return Long.toString((long) speed);
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

        // A null path is a failure no test owns, which says so rather than naming one that did
        // not do it. A host function exception before the first test starts arrives that way.
        String failed = path == null ? "a host function outside every test" : path;
        System.out.println("[GRUG CI] FAIL " + failed);
        System.out.println("[GRUG CI] " + msg);

        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.add("FAIL " + failed);
            GrugErrors.runtimeErrorQueue.add(msg);
        }

        // The failure has its own message, and a record left by the call that aborted here would
        // fail the tick after it as well.
        GrugErrors.takeHostFunctionException();

        // Before destroyCurrentEntity, which puts the test's start back where it belongs, so the
        // test that failed is timed on the run it failed, and the next one starts there.
        if (path != null) recordTestTime(path);
        destroyCurrentEntity();
        finished = true;
        writeTestTimesRecord(path == null ? Collections.emptySet() : Collections.singleton(path));
    }

    /**
     * Fails the run for a host function exception no test call has dealt with, naming the test that
     * owns it, and answers whether it did.
     *
     * <p>A test whose own call threw is failed by the abort of that call, which discards the record
     * on the way, so this sees only what arrived with no call to abort.
     */
    private boolean failOnHostFunctionException() {
        String message = GrugErrors.takeHostFunctionException();
        if (message == null) return false;
        fail(hostFunctionExceptionOwner(), message);
        return true;
    }

    /**
     * The test a host function exception belongs to, or null before the first test has run: the one
     * that is running, else the one that just ended, because the fixtures it left are what the game
     * ticks between two of its calls.
     */
    private String hostFunctionExceptionOwner() {
        // A running test is always the one nextTestIndex names, and the index never passes the
        // last test, so neither needs a range check.
        if (currentEntityHandle != 0) {
            return tests.get(nextTestIndex).getKey();
        }
        if (nextTestIndex > 0) {
            return tests.get(nextTestIndex - 1).getKey();
        }
        return null;
    }

    private void destroyCurrentEntity() {
        // The test is over, so the host entities its member scope created are released with it,
        // whether it passed, failed or aborted. See #883.
        GrugEntities.endTestEntities();
        // Cleared here rather than when the next test starts, so a ctx.expect_error expectation
        // cannot outlive the test that asked for it even when that test failed partway through. A
        // leftover expectation would keep
        // GrugErrors.onRuntimeError recording errors for a test that is no longer running. The
        // captured origin is cleared for the same reason: the next test has to capture its own.
        GrugTestState.testExpectedError = null;
        GrugTestState.testOrigin = null;
        GrugTestState.maxTestTicks = DEFAULT_MAX_TEST_TICKS;
        // The test's start goes back to "no test has started" with the rest, so a test that is not
        // started, such as the one a reload deadline fails, is not timed from this test's start.
        currentTestStartNanos = -1;
        // The test is over, so frames draw again until the next test's room is built, whether it
        // passed, failed or aborted. That also puts drawing back after the last test, so a client
        // that outlives a run is the client the run found. See GrugFrameRequest.
        GrugFrameRequest.endTest();
        // Whether the test passed, expected its error or failed, because a clock left fast would
        // run the next test's room and any capture it takes at a speed it never asked for.
        restoreGameSpeed();
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
        // function error, so the player a test moved is put back without the test having to ask.
        // The runner snapped the player to their block centre at the start of the test, so this
        // undoes that for the next one, and a test that fails partway through no longer leaves the
        // player moved. See #393, #408 and #609.
        GrugCore.getAdapter().restorePlayerAfterTest();
    }

    /** True once the run has stopped for any reason: every test passed, or one failed. */
    public boolean isFinished() {
        return finished;
    }
}
