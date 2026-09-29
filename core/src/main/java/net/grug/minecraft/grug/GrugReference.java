package net.grug.minecraft.grug;

/**
 * Whether this run is driving the reference mod rather than the grug port.
 *
 * <p>A recreation test asks {@code Test.is_reference()} so one test file covers both sides: the
 * port, on every grug loader, and the real mod it recreates, run on its own loader. Assertions
 * target shared state (vanilla blocks, chest contents, dropped item entities) that both sides must
 * produce, and only block names and reference-specific setup branch on the answer.
 *
 * <p>The reference run sets {@link #ENV_VAR} (or the equivalent {@link #PROPERTY}) and loads no
 * grug port, so a normal run and a test run both get {@code false}.
 */
public final class GrugReference {
    /** The environment variable a reference run sets. */
    public static final String ENV_VAR = "GRUG_REFERENCE";

    /** The system property equivalent, so a run can be forced without a wrapper script. */
    public static final String PROPERTY = "grug.reference";

    private GrugReference() {}

    /** Whether this run is the reference run. Normal runs and tests get {@code false}. */
    public static boolean isReferenceRun() {
        return isTruthy(System.getProperty(PROPERTY, System.getenv(ENV_VAR)));
    }

    /**
     * Whether a reference flag counts as set. Anything non-empty except {@code 0} and {@code false}
     * counts, so both {@code GRUG_REFERENCE=1} and {@code GRUG_REFERENCE=true} work, and an unset
     * value or an explicit off does not.
     */
    static boolean isTruthy(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.equals("0")) {
            return false;
        }
        return !value.equalsIgnoreCase("false");
    }
}
