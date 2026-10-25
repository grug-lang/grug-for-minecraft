package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugExactPorts;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Supplier;

/**
 * The state of the one test that is running, shared by the runner that drives it and the {@code
 * Test} host functions that run inside it: its tick, its not-done flag, the error it expects, the
 * errors it raised, its owning mod, whether it is an exact port and its captured origin.
 *
 * <p>grug runs one test at a time, so this is one at a time too: GrugTestRunner sets the fields
 * around each Test.run() call, and the Test host functions set and read them inside it.
 */
public final class GrugTestState {
    private GrugTestState() {}

    /**
     * The context the open {@code run()} call was passed, which is what the context's methods check
     * before they act. A new one is made for every call and cleared when it returns, so a test that
     * saves the context in a member variable and uses it in a later tick is refused.
     */
    public static final class RunContext {
        @GrugGenerated("marker: only a run call makes one")
        private RunContext() {}
    }

    private static RunContext runContext = null;

    /** Opens the context of one {@code run()} call. */
    static RunContext openRunContext() {
        runContext = new RunContext();
        return runContext;
    }

    /** Closes it again, so a saved context no longer matches. */
    static void closeRunContext() {
        runContext = null;
    }

    /** The context of the {@code run()} call that is open right now, or null between calls. */
    public static RunContext runContext() {
        return runContext;
    }

    /**
     * Calls a test's {@code run()} export with a fresh context, which is removed again when the
     * call returns.
     *
     * <p>The context and every host entity the call creates are rooted for this call only, as for a
     * render pass.
     */
    @GrugGenerated(
            "the call into the VM and its context entity: exercising it needs a live grug entity")
    public static boolean callRun(long entityHandle, long exportFnId) {
        boolean[] completed = new boolean[1];
        GrugEntities.withFreshOwner(
                () -> {
                    RunContext context = openRunContext();
                    long contextHandle =
                            GrugEntities.addEntity(GrugEntityType.TestRunContext, context);
                    try {
                        completed[0] = Grug.callExportFn(entityHandle, exportFnId, contextHandle);
                    } finally {
                        GrugEntities.removeEntity(contextHandle);
                        closeRunContext();
                    }
                });
        return completed[0];
    }

    // Set by GrugTestRunner immediately before each Test.run() call; read by the context's tick().
    public static int currentTestTick = 0;
    // Set by the context's not_done(); reset to false by GrugTestRunner immediately before each
    // Test.run() call.
    public static boolean testNotDone = false;

    // Set by the context's expect_error(); cleared by GrugTestRunner when a test starts and when it
    // ends, so
    // it cannot outlive the test that asked for it. While non-null, an export function that aborts
    // on a host error whose message contains this text counts as a pass, which is how the error
    // branches get exercised. It also gates whether onRuntimeError records into testRuntimeErrors,
    // since only a test that expects an error can match a record.
    public static String testExpectedError = null;

    // The runtime errors the running test produced, which is what testExpectedError is matched
    // against. Filled by recordTestRuntimeError, which the engine reaches from inside the host
    // function that failed, so the write happens on the thread running the test and is in place by
    // the time Test.run() returns. GrugTestRunner clears it immediately before each Test.run()
    // call, so it only ever holds what the running test itself raised.
    //
    // It is a separate queue from runtimeErrorQueue because that one is the chat channel, and a
    // loader drains it from the client thread while 1.20.6 runs the test runner on the server
    // thread. Sharing the queue let that drain take the message between the write and the read and
    // leave an expect_error test comparing against an empty string.
    public static final Queue<String> testRuntimeErrors = new ArrayDeque<>();

    // Set by GrugTestRunner to the mod directory holding the running *-Test.grug, so the running
    // test's own about.json decides what it is allowed to assert. The owning mod, rather than
    // whichever mod a screenshot happens to render, is who answers for the promise.
    public static String currentTestMod = null;

    // The origin Test.get_origin() captured first for the running test, so every later call in the
    // same test returns the same position while the player settles. GrugTestRunner clears it when a
    // test starts and when it ends, which is what keeps a setup and its check on one row. See #158.
    public static Vec3 testOrigin = null;

    /**
     * Returns the origin captured for the running test, asking {@code capture} only on the first
     * call. A later call reuses the captured value, so a test that builds a fixture from the origin
     * and checks it from the origin cannot disagree with itself while the player moves.
     */
    public static Vec3 captureTestOrigin(Supplier<Vec3> capture) {
        if (testOrigin == null) {
            testOrigin = capture.get();
        }
        return testOrigin;
    }

    /** Whether the mod owning the running test is a port with no deviations. */
    public static boolean currentTestIsExactPort() {
        return currentTestMod != null && GrugExactPorts.isExactPort(currentTestMod);
    }

    /**
     * Why {@code percent} is not a tolerance {@code Screenshot.tolerance} can set for the running
     * test, or null when it is one.
     *
     * <p>Only a whole percentage from 0 to 100 means anything, because a fractional value has no
     * meaning for a pixel count and the comparison's arithmetic assumes an integer. The range check
     * comes first because a bad percentage is the author's mistake whichever mod they wrote it in.
     * An exact port has to be compared pixel for pixel, so it may not allow any tolerance above 0.
     */
    public static String checkTolerance(double percent) {
        if (percent < 0 || percent > 100 || percent != Math.floor(percent)) {
            return "percent must be a whole number between 0 and 100, but got " + percent + ".";
        }
        if (!GrugExactPorts.allowsTolerance(percent)) {
            return currentTestMod
                    + " is an exact port, so its tests have to compare pixel for pixel and cannot"
                    + " allow a tolerance. Compare without a tolerance, or list the difference as"
                    + " a deviation in its about.json.";
        }
        return null;
    }

    /**
     * Why {@code ticks} is not a number of ticks Test.tick_block_entity can run, or null when it is
     * one. Only a whole number from 0 to {@link Integer#MAX_VALUE} means anything, and anything
     * else is reported rather than rounded, because a test asking for 2.5 ticks has a bug that
     * rounding would hide. The upper bound is the {@code int} loop in {@code
     * Test_tick_block_entity}, which a larger count could never finish.
     */
    public static String checkTickCount(double ticks) {
        if (ticks >= 0 && ticks <= Integer.MAX_VALUE && ticks == Math.floor(ticks)) {
            return null;
        }
        return "The number of ticks has to be a whole number from 0 to "
                + Integer.MAX_VALUE
                + ", and was "
                + ticks
                + ".";
    }
}
