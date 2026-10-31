package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Cuts the JaCoCo agent's coverage data into segments as the test run goes, so the coverage gate
 * can count only the boot before the first test and the tests of {@code mods/coverage/}. What the
 * other mods' tests reach is kept apart, so they never decide the gate and a pull request can run
 * any subset of them. See #626.
 *
 * <p>The cut is per test, not per mod (#786): the runner calls {@link #testStarting(String)} before
 * every test with the test's path, and the segment that just ended is cut then, so each test of a
 * run is its own {@code NNN-<test>.exec} or {@code NNN-<test>.dropped} file in the directory named
 * by {@code -Dgrug.coverage.dir}. Cutting on every test costs one {@code
 * getExecutionData(reset=true)} per test (a copy and a reset of a tens-of- kilobytes probe array),
 * well under a second for a whole suite, and it is what lets a later run carry over the recorded
 * coverage of the tests a diff provably cannot affect instead of rerunning them. The boot before
 * the first test is still its own counted segment, and a test of the counted mod {@link
 * #COUNTED_MOD} is written {@code .exec} while the rest are {@code .dropped}, exactly as the
 * per-mod cut did: the coverage job combines the {@code .exec} files of a runner, so several of
 * them are one run.
 *
 * <p>The {@code .dropped} files exist for one thing only: code that runs once per JVM, which is a
 * class initializer. It ran in whichever segment first used its class, and a reset forgets it, so a
 * class that {@code buildcraft}'s tests happen to use before {@code coverage}'s would otherwise
 * leave its initializer uncovered however well {@code coverage} tests it. {@code
 * combine-coverage.py} counts a class initializer that ran in a dropped segment, and nothing else
 * from one.
 *
 * <p>This replaces the agent's own write on exit, which the CI agent no longer does ({@code
 * output=none}): {@code run-loader.sh} terminates the game as soon as the tests pass, which could
 * interrupt the agent's shutdown-hook write and leave a truncated exec file. Writing each segment
 * from here, and the last one before the run announces that it passed, makes every write
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
    private Path directory;

    /**
     * The segment the agent currently holds the data of: {@link #BOOT} or the running test's path.
     */
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
            agent = attached;
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            throw Grug.fatal("the JaCoCo agent is attached but could not be reached: ", e);
        }
        directory = Paths.get(directoryName);
    }

    /**
     * Whether the coverage gate counts what a segment reached. Anything else is dropped, so the
     * only mod that decides the gate is the one named {@link #COUNTED_MOD}. A segment is the boot
     * or a test's path, and a test is counted by the mod it is in.
     */
    private static boolean counted(String segment) {
        if (BOOT.equals(segment)) return true;
        int slash = segment.indexOf('/');
        String mod = slash < 0 ? segment : segment.substring(0, slash);
        return COUNTED_MOD.equals(mod);
    }

    @Override
    public void testStarting(String testPath) {
        if (agent == null) {
            return;
        }
        cutSegment();
        segment = testPath;
    }

    @Override
    public void runPassed() {
        if (agent != null) {
            cutSegment();
        }
    }

    /**
     * The file-name label of a segment: the boot stays {@code boot}, and a test's path has its
     * slashes flattened, so {@code coverage/tests/a-Test.grug} is the file {@code
     * NNN-coverage.tests.a-Test.grug.<ext>}. Flattening is a bijection for the paths the runner
     * hands over, since the runner owns both the cut and the list of the tests that ran.
     */
    private static String segmentName(String segment) {
        return segment.replace('/', '.');
    }

    /**
     * Writes what the agent holds as the current segment's file and clears it, taken in one call so
     * no hit falls between the two.
     */
    private void cutSegment() {
        byte[] data;
        try {
            data = (byte[]) getExecutionData.invoke(agent, true);
        } catch (ReflectiveOperationException e) {
            throw Grug.fatal("the JaCoCo agent could not hand over its coverage: ", e);
        }
        String extension = counted(segment) ? "exec" : "dropped";
        Path file =
                directory.resolve(
                        String.format(
                                Locale.ROOT,
                                "%03d-%s.%s",
                                written,
                                segmentName(segment),
                                extension));
        try {
            Files.createDirectories(directory);
            Files.write(file, data);
        } catch (IOException e) {
            throw Grug.fatal("could not write the coverage segment " + file + ": ", e);
        }
        written++;
    }
}
