package net.grug.minecraft.core;

/**
 * Flushes the JaCoCo agent's coverage data when a JaCoCo agent is attached.
 *
 * <p>CI attaches JaCoCo to every loader's {@code runClient}, but {@code run-loader.sh} terminates
 * the game as soon as the tests pass, which can interrupt the agent's shutdown-hook write and leave
 * a truncated exec file. Dumping the data from {@link GrugTestRunner} as soon as the tests are known
 * to have passed makes that write deterministic.
 *
 * <p>The agent classes are resolved through the system class loader, since the mod class loader
 * doesn't necessarily delegate to it. The reflection keeps JaCoCo out of core's build, and this is a
 * no-op during normal gameplay.
 */
public final class GrugCoverage {
    private GrugCoverage() {
    }

    public static void dump() {
        try {
            ClassLoader loader = ClassLoader.getSystemClassLoader();
            Class<?> runtime = Class.forName("org.jacoco.agent.rt.RT", false, loader);
            Object agent = runtime.getMethod("getAgent").invoke(null);
            Class<?> agentType = Class.forName("org.jacoco.agent.rt.IAgent", false, loader);
            agentType.getMethod("dump", boolean.class).invoke(agent, false);
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            // No JaCoCo agent is attached, so there is nothing to dump.
            System.err.println("[GRUG CI] JaCoCo coverage dump skipped: " + e);
        }
    }
}
