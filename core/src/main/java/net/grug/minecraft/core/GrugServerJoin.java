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
        if (!"true".equals(env.apply("GRUG_CI")) && env.apply("GRUG_CI_CONNECT") == null) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        return parseMillis(env.apply("GRUG_CI_JOIN_TIMEOUT_MILLIS"));
    }

    /**
     * The positive number of milliseconds in a value, or the default for a value that is null,
     * blank, not a number, zero, negative or too large for a {@code long}. It never throws, because
     * the loaders read it in a static initializer, where an exception would be an {@code
     * ExceptionInInitializerError} that takes the server down. The value is parsed as a {@code
     * long}, so the script's export for any realistic deadline (one above 2685 s overflows an
     * {@code int}) is honoured as given rather than capped.
     */
    static long parseMillis(String value) {
        if (value == null) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        try {
            long millis = Long.parseLong(value.trim());
            return millis > 0 ? millis : DEFAULT_TIMEOUT_MILLIS;
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
    }

    /** Whether the wait that began at {@code waitStartMillis} has lasted the whole timeout. */
    public static boolean timedOut(long waitStartMillis, long nowMillis, long timeoutMillis) {
        return nowMillis - waitStartMillis >= timeoutMillis;
    }

    /** The two lines a run prints when no player joined within {@code timeoutMillis}. */
    public static String[] timeoutReport(long timeoutMillis) {
        return new String[] {
            "[GRUG CI] FAIL world readiness",
            "[GRUG CI] No player joined the dedicated server within "
                    + (timeoutMillis / 1000)
                    + " seconds, so the tests were not started."
        };
    }

    /**
     * The two lines a run prints when the player who started it left, with {@code remaining} left.
     */
    public static String[] starterLeftReport(int remaining) {
        return new String[] {
            "[GRUG CI] FAIL the test player left",
            "[GRUG CI] The player who started the run disconnected while "
                    + remaining
                    + " player(s) were still on the dedicated server."
        };
    }

    /** The two lines a run prints when every player left during it. */
    public static String[] everyoneLeftReport() {
        return new String[] {
            "[GRUG CI] FAIL the test player left",
            "[GRUG CI] Every player disconnected from the dedicated server during the run."
        };
    }

    /** The minimum gap between two reports that the run is still waiting for the world. */
    static final long WAIT_REPORT_INTERVAL_MILLIS = 5000;

    /** Whether the run says it is still waiting: a player is here and the last report is old. */
    public static boolean waitReportDue(boolean hasPlayers, long nowMillis, long lastReportMillis) {
        return hasPlayers && nowMillis - lastReportMillis >= WAIT_REPORT_INTERVAL_MILLIS;
    }

    /** The line a run prints while it waits for the world around a joined player. */
    public static String waitMessage(double x, double y, double z) {
        return "[GRUG CI] waiting for the world: player at " + x + ", " + y + ", " + z;
    }

    /** Prints each line of a report to {@code out}. */
    public static void print(java.io.PrintStream out, String[] lines) {
        for (String line : lines) {
            out.println(line);
        }
    }

    /**
     * The report of a run that has to fail now, or null when it carries on: the wait that began at
     * {@code waitStartMillis} has lasted the whole timeout.
     */
    public static String[] timeoutReportIfDue(
            long waitStartMillis, long nowMillis, long timeoutMillis) {
        return timedOut(waitStartMillis, nowMillis, timeoutMillis)
                ? timeoutReport(timeoutMillis)
                : null;
    }
}
