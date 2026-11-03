package net.grug.minecraft.core;

/**
 * The fault injection of the test-control channel (#1040): environment variables a CI run sets on
 * the joined client so that a request is never answered or is answered late, which the {@code
 * forwarded_} tests in {@code mods/coverage/} then assert as an expected error.
 */
public final class GrugControlFaults {
    private GrugControlFaults() {}

    /** The integer in an environment variable, or {@code fallback} when unset or blank. */
    public static int intFromEnv(String name, int fallback) {
        return parseInt(System.getenv(name), fallback);
    }

    /** The integer in a value, or {@code fallback} when it is null or blank. */
    static int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }
}
