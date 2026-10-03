package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Covers {@link GrugCoverage}'s two outcomes: no agent attached means nothing to say, and an agent
 * that cannot dump takes the run down.
 *
 * <p>The stub agent is compiled from source rather than taken from the real JaCoCo, because this
 * JVM already runs under an agent (Gradle's own) and the interesting cases are the broken ones,
 * which a working agent cannot produce. The stubs keep the real signatures, so the lookup still
 * resolves them by reflection exactly as it does inside a game.
 */
class GrugCoverageTest {

    @TempDir Path tmp;

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

    /** The real {@code IAgent}, whose dump keeps the data when {@code reset} is false. */
    private static final String IAGENT_SOURCE =
            "package org.jacoco.agent.rt;\n"
                    + "import java.io.IOException;\n"
                    + "public interface IAgent {\n"
                    + "    void dump(boolean reset) throws IOException;\n"
                    + "}\n";

    private static final String AGENT_SOURCE =
            "package org.jacoco.agent.rt;\n"
                    + "import java.io.IOException;\n"
                    + "public class StubAgent implements IAgent {\n"
                    + "    public static boolean dumped = false;\n"
                    + "    public static boolean reset = true;\n"
                    + "    private final IOException failure;\n"
                    + "    public StubAgent() { this(null); }\n"
                    + "    public StubAgent(IOException failure) { this.failure = failure; }\n"
                    + "    public void dump(boolean reset) throws IOException {\n"
                    + "        if (failure != null) throw failure;\n"
                    + "        StubAgent.dumped = true;\n"
                    + "        StubAgent.reset = reset;\n"
                    + "    }\n"
                    + "}\n";

    /**
     * A stub agent whose {@code dump} throws {@code failureMessage}, or a working one when it is
     * null. Loaded by a loader that delegates to nothing, so the test JVM's real JaCoCo agent is
     * invisible to it.
     */
    private URLClassLoader stubAgent(String name, String failureMessage) throws Exception {
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
        Object agent =
                failureMessage == null
                        ? stub.getConstructor().newInstance()
                        : stub.getConstructor(IOException.class)
                                .newInstance(new IOException(failureMessage));
        loader.loadClass("org.jacoco.agent.rt.RT")
                .getMethod("install", agentType)
                .invoke(null, agent);
        stub.getField("dumped").setBoolean(null, false);
        return loader;
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

    @Test
    void saysNothingWhenNoAgentIsAttached() throws Exception {
        // A loader that delegates to nothing holds no agent classes, which is what a local run
        // sees.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (URLClassLoader bare = new URLClassLoader(new URL[0], null)) {
            PrintStream savedErr = System.err;
            PrintStream savedOut = System.out;
            try {
                System.setErr(new PrintStream(err, true, "UTF-8"));
                System.setOut(new PrintStream(out, true, "UTF-8"));
                // Not wrapped in an assertion, so a thrown fatal would fail this test too.
                GrugCoverage.dump(bare);
            } finally {
                System.setErr(savedErr);
                System.setOut(savedOut);
            }
        }
        assertEquals("", err.toString("UTF-8"), "a local run must not report the skipped dump");
        assertEquals("", out.toString("UTF-8"));
    }

    @Test
    void dumpsThroughTheAttachedAgent() throws Exception {
        try (URLClassLoader loader = stubAgent("working", null)) {
            GrugCoverage.dump(loader);
            Class<?> stub = loader.loadClass("org.jacoco.agent.rt.StubAgent");
            assertTrue(stub.getField("dumped").getBoolean(null), "the agent was never dumped");
            // reset is false, so the agent still holds the data for its own write on exit.
            assertFalse(
                    stub.getField("reset").getBoolean(null), "the dump must not reset the data");
        }
    }

    @Test
    void aFailingDumpIsFatalAndCarriesTheFailure() throws Exception {
        try (URLClassLoader loader = stubAgent("failing", "the exec file could not be written")) {
            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> GrugCoverage.dump(loader));
            assertTrue(
                    failure.getMessage().startsWith("Broken grug invariant: "),
                    "not an invariant failure: " + failure.getMessage());
            // Reflection reports the agent's own throwable wrapped, so the chain is what carries
            // it.
            Throwable cause = failure.getCause();
            while (cause != null && !(cause instanceof IOException)) cause = cause.getCause();
            assertEquals(
                    "the exec file could not be written",
                    cause == null ? null : cause.getMessage(),
                    "the failure that stopped the dump is missing from the reported cause chain");
        }
    }

    @Test
    void anAgentMissingItsInterfaceIsFatalRatherThanSilent() throws Exception {
        // The runtime class loads, so an agent is attached and only its interface is gone.
        // Reading that as "no agent" is what would hide the failure.
        try (URLClassLoader loader = agentWithoutItsInterface()) {
            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> GrugCoverage.dump(loader));
            assertTrue(failure.getMessage().startsWith("Broken grug invariant: "));
            assertTrue(failure.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    void anAgentRuntimeThatDoesNotLoadIsFatalRatherThanSilent() throws Exception {
        try (URLClassLoader loader = unloadableAgent()) {
            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> GrugCoverage.dump(loader));
            assertTrue(failure.getMessage().startsWith("Broken grug invariant: "));
            assertTrue(failure.getCause() instanceof LinkageError);
        }
    }
}
