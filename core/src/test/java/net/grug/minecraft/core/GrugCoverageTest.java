package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Covers {@link GrugCoverage}: which segments of a run it keeps, and that an agent which cannot be
 * reached or written to takes the run down while no agent at all says nothing.
 *
 * <p>The stub agent is compiled from source rather than taken from the real JaCoCo, because this
 * JVM already runs under an agent (Gradle's own), which a reset would wipe the unit tests' coverage
 * from, and because the interesting cases are the broken ones, which a working agent cannot
 * produce. The stubs keep the real signatures, so the lookup still resolves them by reflection
 * exactly as it does inside a game.
 */
class GrugCoverageTest {

    @TempDir Path tmp;

    /**
     * Drains the fatal a test provoked. {@code Grug.fatal} remembers the first error per thread and
     * rethrows it at the next native call boundary, so leaving one set would fail the next test on
     * this JVM that drives an export function, for a reason unrelated to that test.
     */
    @AfterEach
    void drainPendingFatal() {
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive the test.
        }
    }

    /** The real {@code RT}: it holds the agent and hands it out. */
    private static final String RT_SOURCE =
            "package org.jacoco.agent.rt;\n"
                    + "public final class RT {\n"
                    + "    private static IAgent agent;\n"
                    + "    public static IAgent getAgent() throws IllegalStateException {\n"
                    + "        return agent;\n"
                    + "    }\n"
                    + "    public static void install(IAgent a) { agent = a; }\n"
                    + "}\n";

    /** The real {@code IAgent}, cut down to the two calls the recorder makes. */
    private static final String IAGENT_SOURCE =
            "package org.jacoco.agent.rt;\n"
                    + "public interface IAgent {\n"
                    + "    byte[] getExecutionData(boolean reset);\n"
                    + "    void reset();\n"
                    + "}\n";

    /**
     * Records its calls in {@code log}, hands out {@code data0}, {@code data1} and so on as its
     * snapshots, and throws from the call named by {@code failing} when there is one.
     */
    private static final String AGENT_SOURCE =
            "package org.jacoco.agent.rt;\n"
                    + "public class StubAgent implements IAgent {\n"
                    + "    public static final StringBuilder log = new StringBuilder();\n"
                    + "    private int snapshots = 0;\n"
                    + "    private final String failing;\n"
                    + "    public StubAgent(String failing) { this.failing = failing; }\n"
                    + "    public byte[] getExecutionData(boolean reset) {\n"
                    + "        if (\"snapshot\".equals(failing)) {\n"
                    + "            throw new IllegalStateException(\"the agent is gone\");\n"
                    + "        }\n"
                    + "        log.append(\"snapshot(\").append(reset).append(\") \");\n"
                    + "        return (\"data\" + snapshots++).getBytes();\n"
                    + "    }\n"
                    + "    public void reset() {\n"
                    + "        if (\"reset\".equals(failing)) {\n"
                    + "            throw new IllegalStateException(\"the agent is gone\");\n"
                    + "        }\n"
                    + "        log.append(\"reset \");\n"
                    + "    }\n"
                    + "}\n";

    /**
     * A stub agent that throws from {@code failing} ({@code "snapshot"} or {@code "reset"}) or
     * works when that is null. Loaded by a loader that delegates to nothing, so the test JVM's real
     * JaCoCo agent is invisible to it.
     */
    private URLClassLoader stubAgent(String name, String failing) throws Exception {
        Path classes =
                compile(
                        name,
                        Map.of(
                                "org/jacoco/agent/rt/RT.java",
                                RT_SOURCE,
                                "org/jacoco/agent/rt/IAgent.java",
                                IAGENT_SOURCE,
                                "org/jacoco/agent/rt/StubAgent.java",
                                AGENT_SOURCE));
        URLClassLoader loader = loaderOver(classes);
        Class<?> stub = loader.loadClass("org.jacoco.agent.rt.StubAgent");
        Class<?> agentType = loader.loadClass("org.jacoco.agent.rt.IAgent");
        Object agent = stub.getConstructor(String.class).newInstance(failing);
        loader.loadClass("org.jacoco.agent.rt.RT")
                .getMethod("install", agentType)
                .invoke(null, agent);
        return loader;
    }

    /** What the stub agent was asked to do so far, in order. */
    private static String calls(URLClassLoader loader) throws Exception {
        return loader.loadClass("org.jacoco.agent.rt.StubAgent")
                .getField("log")
                .get(null)
                .toString();
    }

    /**
     * A stub agent whose runtime class loads but whose supertype is not on the class path, which is
     * what a partial or mismatched agent jar looks like at load time.
     */
    private URLClassLoader unloadableAgent() throws Exception {
        Path classes =
                compile(
                        "unloadable",
                        Map.of(
                                "org/jacoco/agent/rt/Missing.java",
                                "package org.jacoco.agent.rt;\npublic class Missing {}\n",
                                "org/jacoco/agent/rt/RT.java",
                                "package org.jacoco.agent.rt;\n"
                                        + "public final class RT extends Missing {}\n"));
        Files.delete(classes.resolve("org/jacoco/agent/rt/Missing.class"));
        return loaderOver(classes);
    }

    /**
     * An agent jar holding {@code RT} but not {@code IAgent}, which is the other half of a partial
     * one. {@code RT} hands out an {@code Object} rather than an {@code IAgent} so that the stub
     * still compiles without the interface it is meant to be missing.
     */
    private URLClassLoader agentWithoutItsInterface() throws Exception {
        Path classes =
                compile(
                        "halfbaked",
                        Map.of(
                                "org/jacoco/agent/rt/RT.java",
                                "package org.jacoco.agent.rt;\n"
                                        + "public final class RT {\n"
                                        + "    public static Object getAgent()\n"
                                        + "            throws IllegalStateException {\n"
                                        + "        return new Object();\n"
                                        + "    }\n"
                                        + "}\n"));
        return loaderOver(classes);
    }

    private URLClassLoader loaderOver(Path classes) throws IOException {
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, null);
    }

    /** Compiles {@code sources} into a fresh directory under the test's temp dir and returns it. */
    private Path compile(String name, Map<String, String> sources) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        Path src = Files.createDirectories(root.resolve("src"));
        Path out = Files.createDirectories(root.resolve("classes"));
        List<File> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toFile());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> problems = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(problems, null, null)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(files);
            ok =
                    compiler.getTask(
                                    null,
                                    fileManager,
                                    problems,
                                    List.of("-d", out.toString()),
                                    null,
                                    units)
                            .call();
        }
        if (!ok) {
            throw new IllegalStateException(
                    "could not compile the stub agent: " + problems.getDiagnostics());
        }
        return out;
    }

    private String segmentFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return "(no directory)";
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining(" "));
        }
    }

    private String content(Path directory, String file) throws IOException {
        return new String(Files.readAllBytes(directory.resolve(file)), StandardCharsets.UTF_8);
    }

    @Test
    void saysNothingAndWritesNothingWhenNoAgentIsAttached() throws Exception {
        // A loader that delegates to nothing holds no agent classes, which is what a local run
        // sees. The directory is not even needed then.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Path directory = tmp.resolve("segments");
        try (URLClassLoader bare = new URLClassLoader(new URL[0], null)) {
            PrintStream savedErr = System.err;
            PrintStream savedOut = System.out;
            try {
                System.setErr(new PrintStream(err, true, "UTF-8"));
                System.setOut(new PrintStream(out, true, "UTF-8"));
                // Not wrapped in an assertion, so a thrown fatal would fail this test too.
                GrugCoverage coverage = new GrugCoverage(bare, directory.toString());
                coverage.testStarting("buildcraft");
                coverage.testStarting("coverage");
                coverage.runPassed();
                new GrugCoverage(bare, null).runPassed();
            } finally {
                System.setErr(savedErr);
                System.setOut(savedOut);
            }
        }
        assertEquals("", err.toString("UTF-8"), "a local run must not report the skipped cut");
        assertEquals("", out.toString("UTF-8"));
        assertEquals("(no directory)", segmentFiles(directory));
    }

    @Test
    void keepsTheBootAndTheCoverageModAndDropsTheRest() throws Exception {
        Path directory = tmp.resolve("segments");
        try (URLClassLoader loader = stubAgent("keeps", null)) {
            GrugCoverage coverage = new GrugCoverage(loader, directory.toString());
            // Several tests of one mod are one segment, so only the change of mod cuts.
            coverage.testStarting("buildcraft");
            coverage.testStarting("buildcraft");
            coverage.testStarting("coverage");
            coverage.testStarting("coverage");
            coverage.testStarting("examplemod");
            coverage.testStarting("jei");
            coverage.runPassed();

            // The boot is snapshotted as buildcraft starts, buildcraft is reset away as coverage
            // starts, coverage is snapshotted as examplemod starts, examplemod is reset away as jei
            // starts, and jei, the last segment, is not counted so it is not even reset.
            assertEquals("snapshot(true) reset snapshot(true) reset ", calls(loader));
            assertEquals("000-boot.exec 001-coverage.exec", segmentFiles(directory));
            assertEquals("data0", content(directory, "000-boot.exec"));
            assertEquals("data1", content(directory, "001-coverage.exec"));
        }
    }

    @Test
    void writesTheCoverageSegmentThatEndsTheRun() throws Exception {
        Path directory = tmp.resolve("segments");
        try (URLClassLoader loader = stubAgent("ends", null)) {
            GrugCoverage coverage = new GrugCoverage(loader, directory.toString());
            coverage.testStarting("buildcraft");
            coverage.testStarting("coverage");
            coverage.runPassed();

            assertEquals("snapshot(true) reset snapshot(true) ", calls(loader));
            assertEquals("000-boot.exec 001-coverage.exec", segmentFiles(directory));
        }
    }

    @Test
    void aRunOfTheCoverageModAlone() throws Exception {
        Path directory = tmp.resolve("segments");
        try (URLClassLoader loader = stubAgent("alone", null)) {
            GrugCoverage coverage = new GrugCoverage(loader, directory.toString());
            coverage.testStarting("coverage");
            coverage.testStarting("coverage");
            coverage.runPassed();

            assertEquals("snapshot(true) snapshot(true) ", calls(loader));
            assertEquals("000-boot.exec 001-coverage.exec", segmentFiles(directory));
        }
    }

    @Test
    void aRunWithoutTheCoverageModKeepsOnlyTheBoot() throws Exception {
        // The gate then fails for the coverage the missing mod would have added, which is the
        // point.
        Path directory = tmp.resolve("segments");
        try (URLClassLoader loader = stubAgent("without", null)) {
            GrugCoverage coverage = new GrugCoverage(loader, directory.toString());
            coverage.testStarting("jei");
            coverage.runPassed();

            assertEquals("snapshot(true) ", calls(loader));
            assertEquals("000-boot.exec", segmentFiles(directory));
        }
    }

    @Test
    void aRepeatedSuiteCutsEverySegmentAgain() throws Exception {
        Path directory = tmp.resolve("segments");
        try (URLClassLoader loader = stubAgent("repeats", null)) {
            GrugCoverage coverage = new GrugCoverage(loader, directory.toString());
            for (int pass = 0; pass < 2; pass++) {
                coverage.testStarting("buildcraft");
                coverage.testStarting("coverage");
                coverage.testStarting("jei");
            }
            coverage.runPassed();

            assertEquals(
                    "000-boot.exec 001-coverage.exec 002-coverage.exec", segmentFiles(directory));
        }
    }

    @Test
    void creatingTheRecorderWithoutADirectoryIsFatalWhenAnAgentIsAttached() throws Exception {
        try (URLClassLoader loader = stubAgent("nodir", null)) {
            for (String directory : new String[] {null, ""}) {
                IllegalStateException failure =
                        assertThrows(
                                IllegalStateException.class,
                                () -> new GrugCoverage(loader, directory));
                assertTrue(
                        failure.getMessage().contains("-D" + GrugCoverage.DIRECTORY_PROPERTY),
                        "the property that is missing is not named: " + failure.getMessage());
            }
        }
    }

    @Test
    void theEnvironmentNamesItsDirectoryByTheSystemProperty() {
        // Under the build's own agent when there is one, which this only resolves and never cuts:
        // a cut would reset the data the unit tests have gathered so far.
        String saved = System.getProperty(GrugCoverage.DIRECTORY_PROPERTY);
        System.setProperty(GrugCoverage.DIRECTORY_PROPERTY, tmp.resolve("env").toString());
        try {
            assertNotNull(GrugCoverage.fromEnvironment());
        } finally {
            if (saved == null) {
                System.clearProperty(GrugCoverage.DIRECTORY_PROPERTY);
            } else {
                System.setProperty(GrugCoverage.DIRECTORY_PROPERTY, saved);
            }
        }
    }

    /** The innermost cause of {@code failure} that is a {@code type}, or null. */
    private static Throwable causeOfType(Throwable failure, Class<? extends Throwable> type) {
        Throwable cause = failure.getCause();
        while (cause != null && !type.isInstance(cause)) cause = cause.getCause();
        return cause;
    }

    @Test
    void aFailingSnapshotIsFatalAndCarriesTheFailure() throws Exception {
        try (URLClassLoader loader = stubAgent("badsnapshot", "snapshot")) {
            GrugCoverage coverage = new GrugCoverage(loader, tmp.resolve("segments").toString());
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class, () -> coverage.testStarting("buildcraft"));
            assertTrue(
                    failure.getMessage().startsWith("Broken grug invariant: "),
                    "not an invariant failure: " + failure.getMessage());
            // Reflection reports the agent's own throwable wrapped, so the chain is what carries
            // it.
            Throwable cause = causeOfType(failure, IllegalStateException.class);
            assertEquals(
                    "the agent is gone",
                    cause == null ? null : cause.getMessage(),
                    "the failure that stopped the cut is missing from the reported cause chain");
        }
    }

    @Test
    void aFailingResetIsFatal() throws Exception {
        try (URLClassLoader loader = stubAgent("badreset", "reset")) {
            GrugCoverage coverage = new GrugCoverage(loader, tmp.resolve("segments").toString());
            // The boot is snapshotted, which works, and the next cut resets.
            coverage.testStarting("buildcraft");
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class, () -> coverage.testStarting("coverage"));
            assertTrue(failure.getMessage().startsWith("Broken grug invariant: "));
            assertTrue(failure.getMessage().contains("reset"));
        }
    }

    @Test
    void aSegmentThatCannotBeWrittenIsFatalAndCarriesTheFailure() throws Exception {
        // A file where the directory should be, so neither can the directory be made nor written
        // in.
        Path blocked = Files.writeString(tmp.resolve("blocked"), "in the way");
        try (URLClassLoader loader = stubAgent("unwritable", null)) {
            GrugCoverage coverage = new GrugCoverage(loader, blocked.toString());
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class, () -> coverage.testStarting("buildcraft"));
            assertTrue(failure.getMessage().startsWith("Broken grug invariant: "));
            assertTrue(failure.getMessage().contains("000-boot.exec"));
            assertTrue(causeOfType(failure, IOException.class) != null);
        }
    }

    @Test
    void anAgentMissingItsInterfaceIsFatalRatherThanSilent() throws Exception {
        // The runtime class loads, so an agent is attached and only its interface is gone.
        // Reading that as "no agent" is what would hide the failure.
        try (URLClassLoader loader = agentWithoutItsInterface()) {
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> new GrugCoverage(loader, tmp.toString()));
            assertTrue(failure.getMessage().startsWith("Broken grug invariant: "));
            assertTrue(failure.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    void anAgentRuntimeThatDoesNotLoadIsFatalRatherThanSilent() throws Exception {
        try (URLClassLoader loader = unloadableAgent()) {
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> new GrugCoverage(loader, tmp.toString()));
            assertTrue(failure.getMessage().startsWith("Broken grug invariant: "));
            assertTrue(failure.getCause() instanceof LinkageError);
        }
    }
}
