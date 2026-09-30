package net.grug.minecraft.grug;

import java.io.File;

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

    /** The environment variable that points a run at a grug mods directory of its choosing. */
    public static final String MODS_DIR_ENV_VAR = "GRUG_MODS_DIR";

    /** The system property equivalent of {@link #MODS_DIR_ENV_VAR}. */
    public static final String MODS_DIR_PROPERTY = "grug.mods.dir";

    /** The environment variable a CI run sets, so a run never certifies its own golden. */
    public static final String CI_ENV_VAR = "GRUG_CI";

    /** The system property equivalent of {@link #CI_ENV_VAR}. */
    public static final String CI_PROPERTY = "grug.ci";

    private GrugReference() {}

    /** Whether this run is the reference run. Normal runs and tests get {@code false}. */
    public static boolean isReferenceRun() {
        return isTruthy(System.getProperty(PROPERTY, System.getenv(ENV_VAR)));
    }

    /**
     * Whether this run is CI. A CI run must not capture a golden it is supposed to compare against,
     * so the screenshot verifier branches on this. Anything but {@code 0} and {@code false} counts,
     * exactly as for {@link #isReferenceRun()}.
     */
    public static boolean isCiRun() {
        return isTruthy(System.getProperty(CI_PROPERTY, System.getenv(CI_ENV_VAR)));
    }

    /**
     * The grug mods directory a run wants instead of the loader's own, or null to let the loader
     * choose. A reference run points this at a harness-only directory so no grug port loads
     * alongside the reference mod.
     *
     * <p>A set-but-missing path is returned as-is rather than treated as unset: silently falling
     * back would load the port into what is supposed to be a reference run, which is the one
     * failure a reference run must never hide. The loader reports it instead.
     */
    public static File modsDirOverride() {
        String value = System.getProperty(MODS_DIR_PROPERTY, System.getenv(MODS_DIR_ENV_VAR));
        if (value == null || value.isEmpty()) {
            return null;
        }
        return new File(value);
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
