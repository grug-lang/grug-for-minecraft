package net.grug.minecraft.core;

import java.util.function.Function;

/**
 * When a dedicated-server run gives up waiting for a client to join (#1023).
 *
 * <p>The decision is a pure function of the clock and one number, so it lives here where a unit
 * test reaches every case, and the loaders' {@code GrugServerRun} only reads the clock. The number
 * is tied to the script that launches the run: {@code run-multiplayer.sh} waits {@link
 * #RUN_DEADLINE_SECONDS} seconds for the suite and exports {@code GRUG_CI_JOIN_TIMEOUT_MILLIS} as
 * four fifths of its own deadline, so the server always reports "no player joined" before the
 * script's generic timeout can fire and hide the cause. Without the variable the default below is
 * the same four fifths of the default deadline.
 */
public final class GrugServerJoin {
    private GrugServerJoin() {}

    /**
     * The script's default deadline for the whole run, in seconds ({@code GRUG_CI_RUN_TIMEOUT}).
     */
    public static final int RUN_DEADLINE_SECONDS = 300;

    /** The join timeout when the script did not export one: four fifths of the default deadline. */
    public static final long DEFAULT_TIMEOUT_MILLIS = RUN_DEADLINE_SECONDS * 800L;

    /** The join timeout this run uses. */
    public static long timeoutMillis() {
        return timeoutFrom(System::getenv);
    }

    /** The same, reading variables through {@code env}. */
    static long timeoutFrom(Function<String, String> env) {
        int millis = GrugControlFaults.intFrom(env, "GRUG_CI_JOIN_TIMEOUT_MILLIS", 0);
        return millis > 0 ? millis : DEFAULT_TIMEOUT_MILLIS;
    }

    /** Whether the wait that began at {@code waitStartMillis} has lasted the whole timeout. */
    public static boolean timedOut(long waitStartMillis, long nowMillis, long timeoutMillis) {
        return nowMillis - waitStartMillis >= timeoutMillis;
    }
}
