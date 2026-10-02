package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private final TestEntityOps ops;
    private final List<Map.Entry<String, Long>> tests;
    private final int totalCount;

    private int nextTestIndex = 0;
    private int passedCount = 0;

    /** 0 when no test is active, meaning "start the next one". */
    private long currentEntityHandle = 0;

    private long currentRunFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private int currentTick = 0;

    private boolean finished = false;

    public GrugTestRunner() {
        this(
                Grug.fileIds,
                GrugScreenshots.validateReferenceTrees(
                        GrugCore.getAdapter().getGrugModsDirectory()));
    }

    /** Visible for tests: builds a runner over the given files without touching GrugCore. */
    public GrugTestRunner(Map<String, Long> fileIds, List<String> referenceErrors) {
        this(fileIds, referenceErrors, NATIVE_OPS);
    }

    public GrugTestRunner(
            Map<String, Long> fileIds, List<String> referenceErrors, TestEntityOps ops) {
        this.ops = ops;

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
        // Anything already queued was reported during startup, before any test ran, and a run owns
        // the queue from here. Leaving it would let a test that expects an error match a startup
        // message that happened to be waiting, since the expect_error path drains the same queue.
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
            if (passedCount == totalCount) {
                System.out.println("[GRUG CI] ALL " + totalCount + " TESTS PASSED");
                GrugCoverage.dump();
            }
            finished = true;
            return;
        }

        String path = tests.get(nextTestIndex).getKey();

        Grug.currentTestTick = currentTick;
        Grug.testNotDone = false;
        Grug.currentTestMod = owningMod(path);

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
                // matches what the test asked to see, so a test cannot pass on the wrong error.
                String actual;
                synchronized (Grug.runtimeErrorQueue) {
                    actual = String.join("\n", Grug.runtimeErrorQueue);
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

        destroyCurrentEntity();
        nextTestIndex++;
        currentTick = 0;
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
        Grug.testExpectedError = null;
        // Also cleared in destroyCurrentEntity. Doing both ends means a run that was restarted or
        // aborted cannot inherit an override from a previous one.
        Grug.testFidelityOverride = null;
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
        // Cleared here rather than when the next test starts, so a Test.force_fidelity override
        // cannot outlive the test that asked for it even when that test failed partway through.
        Grug.testFidelityOverride = null;
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
