package net.grug.minecraft.core;

import java.util.function.Function;

/**
 * The fault injection of the test-control channel (#1040): environment variables a CI run sets on
 * the joined client so that a request is never answered or is answered late, which the {@code
 * forwarded_} tests in {@code mods/coverage/} then assert as an expected error. They are honoured
 * only in a CI run ({@code GRUG_CI} on a server, {@code GRUG_CI_CONNECT} on a joined client), so a
 * stray variable on a player's machine cannot break a real capture.
 */
public final class GrugControlFaults {
    private GrugControlFaults() {}

    /** The integer in an environment variable of a CI run, or {@code fallback} otherwise. */
    public static int intFromEnv(String name, int fallback) {
        return intFrom(System::getenv, name, fallback);
    }

    /** The same, reading variables through {@code env}. */
    static int intFrom(Function<String, String> env, String name, int fallback) {
        if (!"true".equals(env.apply("GRUG_CI")) && env.apply("GRUG_CI_CONNECT") == null) {
            return fallback;
        }
        return parseInt(env.apply(name), fallback);
    }

    /** The integer in a value, or {@code fallback} when it is null or blank. */
    static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }
}
