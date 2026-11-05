package net.grug.minecraft.core;

/**
 * Decides when the test runner turns on the GL capability that normalizes normals, so a capture can
 * prove it does not depend on a state an earlier draw leaked.
 *
 * <p>Beta 1.7.3's slime renderer turns {@code GL_NORMALIZE} on and no class turns it off again, so
 * a session that rendered a slime anywhere before the JEI item list opened shades the overlay's
 * item icons with unit normals: the item draw scales its model by ten, and the left face of every
 * cube then renders about 1.9 times brighter. Whether a slime is rendered at all is the world's mob
 * spawning, which is why that capture flaked (#1137). A test whose file name starts with {@link
 * #PREFIX} gets the capability turned on at {@link #TICK}, the way the slime renderer leaves it,
 * and opens its screen after that tick, so its capture fails unless the overlay's item draw pins
 * the capability off instead of inheriting it.
 *
 * <p>A reference run never forces it: the reference mod's own icons are not the port's to change.
 * The decision is a pure function of the path, the tick and the run, like {@link
 * GrugForcedAutosave}, so it is tested without a runner or a client.
 */
public final class GrugForcedNormalization {
    /** The prefix of a test file name that asks for the forced capability. */
    public static final String PREFIX = "forced_normalization_";

    /** The tick of a marked test before which the runner turns the capability on. */
    public static final long TICK = 100;

    private GrugForcedNormalization() {}

    /**
     * Whether the runner turns the capability on before the test's tick {@code tick} runs.
     *
     * @param testPath the path of the test, as the runner lists it
     * @param tick the tick of the test that is about to run
     * @param referenceRun whether this run drives the reference mod rather than a grug port
     */
    public static boolean isDue(String testPath, long tick, boolean referenceRun) {
        if (referenceRun || tick != TICK) {
            return false;
        }
        String name = testPath.substring(testPath.lastIndexOf('/') + 1);
        return name.startsWith(PREFIX);
    }
}
