package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugExactPorts;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Supplier;

/**
 * The state of the one test that is running, shared by the runner that drives it and the {@code
 * Test} host functions that run inside it: its tick, its not-done flag, the error it expects, the
 * errors it raised, its owning mod, its exact-port rule and its captured origin.
 *
 * <p>grug runs one test at a time, so this is one at a time too: GrugTestRunner sets the fields
 * around each Test.run() call, and the Test host functions set and read them inside it.
 */
public final class GrugTestState {
    private GrugTestState() {}

    // Set by GrugTestRunner immediately before each Test.run() call; read by Test.tick().
    public static int currentTestTick = 0;
    // Set by Test.not_done(); reset to false by GrugTestRunner immediately before each
    // Test.run() call.
    public static boolean testNotDone = false;

    // Set by Test.expect_error(); cleared by GrugTestRunner when a test starts and when it ends, so
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

    // Set by Test.force_exact_port(); reset to false by GrugTestRunner immediately before each
    // Test.run() call, so one test's override cannot go on deciding the next one's assertions. It
    // can only tighten a test's rule: a mod that is an exact port stays one whatever it is set to.
    public static boolean testForcedExactPort = false;

    // The origin Test.get_origin() captured first for the running test, so every later call in the
    // same test returns the same position while the player settles. GrugTestRunner clears it when a
    // test starts and when it ends, which is what keeps a setup and its check on one row. See #158.
    public static Vec3 testOrigin = null;

    // The ticks Test.tick_block_entity has run in the running test, which Level.get_time adds to
    // the world's time because those ticks pass within one game tick. GrugTestRunner clears it when
    // a test starts and when it ends, so one test's clock cannot leak into the next.
    public static long testClockOffset = 0;

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

    /**
     * Whether the mod owning the running test is a port with no deviations, honouring a test-only
     * override. The override can tighten the rule, which is how a mod that is not itself an exact
     * port covers the rules for one, but it cannot loosen a mod that is.
     */
    public static boolean currentTestIsExactPort() {
        if (currentTestMod != null && GrugExactPorts.isExactPort(currentTestMod)) {
            return true;
        }
        return testForcedExactPort;
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
