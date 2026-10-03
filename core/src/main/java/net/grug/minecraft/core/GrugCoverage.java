package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;

/**
 * Flushes the JaCoCo agent's coverage data when a JaCoCo agent is attached.
 *
 * <p>CI attaches JaCoCo to every loader's {@code runClient}, but {@code run-loader.sh} terminates
 * the game as soon as the tests pass, which can interrupt the agent's shutdown-hook write and leave
 * a truncated exec file. Dumping the data from {@link GrugTestRunner} as soon as the tests are
 * known to have passed makes that write deterministic.
 *
 * <p>The agent classes are resolved through the system class loader, since the mod class loader
 * doesn't necessarily delegate to it. The reflection keeps JaCoCo out of core's build.
 *
 * <p>Not finding the agent is not a failure. A local run has none attached and so has nothing to
 * dump, which ends the lookup silently. Everything past that point is a failure, and it takes the
 * run down: a CI run that could not record its coverage must not report success and leave the
 * coverage gate to infer the loss from absent data.
 */
public final class GrugCoverage {
    private GrugCoverage() {}

    @GrugGenerated("CI coverage dump: only has work when a JaCoCo agent is attached")
    public static void dump() {
        dump(ClassLoader.getSystemClassLoader());
    }

    /**
     * Visible for tests: resolves the agent through {@code loader} rather than the system class
     * loader, so a test can drive the no-agent case and a failing dump without a real agent.
     */
    @GrugGenerated("CI coverage dump: only has work when a JaCoCo agent is attached")
    static void dump(ClassLoader loader) {
        Class<?> runtime;
        try {
            runtime = Class.forName("org.jacoco.agent.rt.RT", false, loader);
        } catch (ClassNotFoundException e) {
            // No JaCoCo agent is attached, so there is nothing to dump. Nothing went wrong, so this
            // says nothing either.
            return;
        } catch (LinkageError | SecurityException e) {
            // The agent's runtime class is on the class path but does not load, so an agent is
            // attached and broken rather than absent.
            throw Grug.fatal(
                    "the JaCoCo agent is attached but org.jacoco.agent.rt.RT does not load: ", e);
        }

        try {
            Object agent = runtime.getMethod("getAgent").invoke(null);
            Class<?> agentType = Class.forName("org.jacoco.agent.rt.IAgent", false, loader);
            agentType.getMethod("dump", boolean.class).invoke(agent, false);
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            throw Grug.fatal("the JaCoCo agent is attached but could not dump its coverage: ", e);
        }
    }
}
