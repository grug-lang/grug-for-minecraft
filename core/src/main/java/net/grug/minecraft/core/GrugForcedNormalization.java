package net.grug.minecraft.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether the test runner turns on the GL capability that normalizes normals, so a capture
 * can prove it does not depend on a state an earlier draw leaked.
 *
 * <p>Beta 1.7.3's slime renderer turns {@code GL_NORMALIZE} on and no class turns it off again, so
 * a session that rendered a slime anywhere before the JEI item list opened shades the overlay's
 * item icons with unit normals: the item draw scales its model by ten, and the left face of every
 * cube then renders about 1.9 times brighter. Whether a slime is rendered at all is the world's mob
 * spawning, which is why that capture flaked (#1137). A test whose file name ends with {@link
 * #MARKER} gets the capability turned on before its first tick, the way the slime renderer leaves
 * it, so every frame and every capture it takes runs with it, and fails unless the overlay's item
 * draw pins the capability off instead of inheriting it.
 *
 * <p>The mark is a suffix, not a prefix, so the marked test keeps its place in the suite: the JEI
 * item list capture runs between the tests it ran between before, and a suite order that changed
 * here would be a second change in a pull request about one flake.
 *
 * <p>The state goes on before the test's first tick rather than at a tick a test and the runner
 * each keep a copy of, so the test cannot open its screen before the state is on, and there is no
 * second number that can disagree with this one (#1144).
 *
 * <p>A reference run never forces it, because the reference mod's own icons are not the port's to
 * change, and neither does a dedicated server, which has no GL context: the decision is a pure
 * function of the path, whether this is a dedicated server and the run, like {@link
 * GrugForcedAutosave}, so it is tested without a runner or a client.
 */
public final class GrugForcedNormalization {
    /** The mark the file name of a test that asks for the forced capability ends with. */
    public static final String MARKER = "_forced_normalization";

    private GrugForcedNormalization() {}

    /**
     * Whether the runner turns the capability on before the test's first tick runs.
     *
     * @param testPath the path of the test, as the runner lists it
     * @param dedicatedServer whether this process is a dedicated server, which has no GL context
     * @param referenceRun whether this run drives the reference mod rather than a grug port
     */
    public static boolean appliesTo(
            String testPath, boolean dedicatedServer, boolean referenceRun) {
        if (dedicatedServer || referenceRun) {
            return false;
        }
        String name = testPath.substring(testPath.lastIndexOf('/') + 1);
        return name.endsWith(MARKER + "-Test.grug");
    }

    /**
     * Test only: the capabilities a restore has to turn off, which is every capability the force
     * turned on and no capability the game itself left on. The marked test models a leak, so a
     * force can find one already on, and putting that one back means leaving it on (#1144).
     *
     * <p>The choice is here rather than in each loader, so it is tested with both answers of each
     * capability and no loader carries a branch that only a capability the game leaked meets, as
     * {@link GrugRenderSettle#onFrame} keeps a frame's rare case out of the loaders. A loader asks
     * this in the tick a marked test ends and disables what it names.
     *
     * @param normalizeWasOn what {@code GL_NORMALIZE} had when the force found it
     * @param rescaleNormalWasOn what {@code GL_RESCALE_NORMAL} had when the force found it
     * @param normalizeCapability the loader's own {@code GL_NORMALIZE} constant
     * @param rescaleNormalCapability the loader's own {@code GL_RESCALE_NORMAL} constant
     * @return the capabilities to disable, which is empty when the force found both already on
     */
    public static List<Integer> capabilitiesToTurnOff(
            boolean normalizeWasOn,
            boolean rescaleNormalWasOn,
            int normalizeCapability,
            int rescaleNormalCapability) {
        List<Integer> capabilities = new ArrayList<>(2);
        if (!normalizeWasOn) {
            capabilities.add(normalizeCapability);
        }
        if (!rescaleNormalWasOn) {
            capabilities.add(rescaleNormalCapability);
        }
        return capabilities;
    }

    /**
     * Whether the client's two capabilities are what the force found, now that the restore ran,
     * which is the answer a restore reports. A disable that did not take leaves one of them
     * different, and the runner fails the marked test rather than shading a later capture (#1144).
     *
     * <p>The comparison is here rather than in each loader, so it is tested with every combination
     * of the two answers and no loader carries a branch that only a mismatched state meets, as
     * {@link GrugRenderSettle#onFrame} keeps a frame's rare case out of the loaders. A loader reads
     * the client's GL state and passes both answers in.
     *
     * @param normalizeNow what {@code GL_NORMALIZE} has now, after the restore
     * @param rescaleNormalNow what {@code GL_RESCALE_NORMAL} has now, after the restore
     * @param normalizeWasOn what {@code GL_NORMALIZE} had when the force found it
     * @param rescaleNormalWasOn what {@code GL_RESCALE_NORMAL} had when the force found it
     */
    public static boolean matchesFound(
            boolean normalizeNow,
            boolean rescaleNormalNow,
            boolean normalizeWasOn,
            boolean rescaleNormalWasOn) {
        return normalizeNow == normalizeWasOn & rescaleNormalNow == rescaleNormalWasOn;
    }
}
