package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Cuts the JaCoCo agent's coverage data into segments as the test run goes, and keeps only the
 * segments the coverage gate counts: the boot before the first test, and the tests of {@code
 * mods/coverage/}. What the other mods' tests reach is dropped, so they never decide the gate and a
 * pull request can run any subset of them. See #626.
 *
 * <p>The runner sorts the tests by path, so the run is a sequence of segments, one per stretch of
 * tests of the same mod: the boot, then for example {@code buildcraft}, {@code coverage}, {@code
 * examplemod} and {@code jei}. Whenever the next test belongs to another mod the segment that just
 * ended is cut: a counted one is written to its own {@code NNN-<segment>.exec} file in the
 * directory named by {@code -Dgrug.coverage.dir}, and any other is thrown away. The agent resets as
 * part of the cut, so the data in it is always that of the current segment. The coverage job
 * combines the files of a runner, so several of them are one run.
 *
 * <p>This replaces the agent's own write on exit, which the CI agent no longer does ({@code
 * output=none}): {@code run-loader.sh} terminates the game as soon as the tests pass, which could
 * interrupt the agent's shutdown-hook write and leave a truncated exec file. Writing each counted
 * segment from here, and the last one before the run announces that it passed, makes every write
 * deterministic.
 *
 * <p>The agent classes are resolved through the system class loader, since the mod class loader
 * doesn't necessarily delegate to it. The reflection keeps JaCoCo out of core's build.
 *
 * <p>Not finding the agent is not a failure. A local run has none attached and so has nothing to
 * cut, which leaves this inactive and silent. Everything past that point is a failure, and it takes
 * the run down: a CI run that could not record its coverage must not report success and leave the
 * coverage gate to infer the loss from absent data.
 */
public final class GrugCoverage implements GrugTestRunner.CoverageRecorder {
    /** The system property naming the directory the counted segments are written to. */
    static final String DIRECTORY_PROPERTY = "grug.coverage.dir";

    /** The mod whose tests the coverage gate counts. */
    static final String COUNTED_MOD = "coverage";

    /** The segment before the first test, which is counted: it is how the game and mods boot. */
    static final String BOOT = "boot";

    /** Null when no agent is attached, which leaves every call a no-op. */
    private Object agent;

    private Method getExecutionData;
    private Method reset;
    private Path directory;

    /** The segment the agent currently holds the data of. */
    private String segment = BOOT;

    /** How many segments were written, which numbers the files so they sort in run order. */
    private int written = 0;

    /** The recorder of a game run: the system class loader's agent and the property's directory. */
    public static GrugCoverage fromEnvironment() {
        return new GrugCoverage(
                ClassLoader.getSystemClassLoader(), System.getProperty(DIRECTORY_PROPERTY));
    }

    /**
     * Visible for tests: resolves the agent through {@code loader} rather than the system class
     * loader, so a test can drive the no-agent case and a failing agent without a real one, and cut
     * its segments into {@code directoryName}, which may be null or empty.
     */
    GrugCoverage(ClassLoader loader, String directoryName) {
        Class<?> runtime;
        try {
            runtime = Class.forName("org.jacoco.agent.rt.RT", false, loader);
        } catch (ClassNotFoundException e) {
            // No JaCoCo agent is attached, so there is nothing to cut. Nothing went wrong, so this
            // says nothing either.
            return;
        } catch (LinkageError | SecurityException e) {
            // The agent's runtime class is on the class path but does not load, so an agent is
            // attached and broken rather than absent.
            throw Grug.fatal(
                    "the JaCoCo agent is attached but org.jacoco.agent.rt.RT does not load: ", e);
        }

        if (directoryName == null || directoryName.isEmpty()) {
            throw Grug.fatal(
                    "the JaCoCo agent is attached but -D"
                            + DIRECTORY_PROPERTY
                            + " does not name the directory to write its coverage segments to");
        }

        try {
            Object attached = runtime.getMethod("getAgent").invoke(null);
            Class<?> agentType = Class.forName("org.jacoco.agent.rt.IAgent", false, loader);
            getExecutionData = agentType.getMethod("getExecutionData", boolean.class);
            reset = agentType.getMethod("reset");
            agent = attached;
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            throw Grug.fatal("the JaCoCo agent is attached but could not be reached: ", e);
        }
        directory = Paths.get(directoryName);
    }

    /**
     * Whether the coverage gate counts what a segment reached. Anything else is dropped, so the
     * only mod that decides the gate is the one named {@link #COUNTED_MOD}.
     */
    private static boolean counted(String segment) {
        return BOOT.equals(segment) || COUNTED_MOD.equals(segment);
    }

    @Override
    public void testStarting(String mod) {
        if (agent == null || mod.equals(segment)) {
            return;
        }
        if (counted(segment)) {
            write(snapshotAndReset());
        } else {
            call(reset);
        }
        segment = mod;
    }

    @Override
    public void runPassed() {
        // The data of an uncounted last segment is of no use to anyone, and the process ends right
        // after, so it is not even reset.
        if (agent != null && counted(segment)) {
            write(snapshotAndReset());
        }
    }

    /** What the agent holds, taken and cleared in one call so no hit falls between the two. */
    private byte[] snapshotAndReset() {
        return (byte[]) call(getExecutionData, true);
    }

    private void write(byte[] data) {
        Path file = directory.resolve(String.format(Locale.ROOT, "%03d-%s.exec", written, segment));
        try {
            Files.createDirectories(directory);
            Files.write(file, data);
        } catch (IOException e) {
            throw Grug.fatal("could not write the coverage segment " + file + ": ", e);
        }
        written++;
    }

    private Object call(Method method, Object... arguments) {
        try {
            return method.invoke(agent, arguments);
        } catch (ReflectiveOperationException e) {
            throw Grug.fatal("the JaCoCo agent could not " + method.getName() + ": ", e);
        }
    }
}
