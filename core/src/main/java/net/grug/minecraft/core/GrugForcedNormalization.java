package net.grug.minecraft.core;

/**
 * Decides when the test runner turns on the GL capability that normalizes normals, so a capture can
 * prove it does not depend on a state an earlier draw leaked.
 *
 * <p>Beta 1.7.3's slime renderer turns {@code GL_NORMALIZE} on and no class turns it off again, so
 * a session that rendered a slime anywhere before the JEI item list opened shades the overlay's
 * item icons with unit normals: the item draw scales its model by ten, and the left face of every
 * cube then renders about 1.9 times brighter. Whether a slime is rendered at all is the world's mob
 * spawning, which is why that capture flaked (#1137). A test whose file name ends with {@link
 * #MARKER} gets the capability turned on at {@link #TICK}, the way the slime renderer leaves it,
 * and opens its screen after that tick, so its capture fails unless the overlay's item draw pins
 * the capability off instead of inheriting it.
 *
 * <p>The mark is a suffix, not a prefix, so the marked test keeps its place in the suite: the JEI
 * item list capture runs between the tests it ran between before, and a suite order that changed
 * here would be a second change in a pull request about one flake.
 *
 * <p>A reference run never forces it, because the reference mod's own icons are not the port's to
 * change, and neither does a dedicated server, which has no GL context: the decision is a pure
 * function of the path, the tick, whether this is a dedicated server and the run, like {@link
 * GrugForcedAutosave}, so it is tested without a runner or a client.
 */
public final class GrugForcedNormalization {
    /** The mark the file name of a test that asks for the forced capability ends with. */
    public static final String MARKER = "_forced_normalization";

    /** The tick of a marked test before which the runner turns the capability on. */
    public static final long TICK = 100;

    private GrugForcedNormalization() {}

    /**
     * Whether the runner turns the capability on before the test's tick {@code tick} runs.
     *
     * @param testPath the path of the test, as the runner lists it
     * @param tick the tick of the test that is about to run
     * @param dedicatedServer whether this process is a dedicated server, which has no GL context
     * @param referenceRun whether this run drives the reference mod rather than a grug port
     */
    public static boolean isDue(
            String testPath, long tick, boolean dedicatedServer, boolean referenceRun) {
        if (dedicatedServer || referenceRun || tick != TICK) {
            return false;
        }
        String name = testPath.substring(testPath.lastIndexOf('/') + 1);
        return name.endsWith(MARKER + "-Test.grug");
    }
}
