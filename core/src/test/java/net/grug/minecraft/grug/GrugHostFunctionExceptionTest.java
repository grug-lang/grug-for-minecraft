package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Drives a host function that really throws through the JNI wrapper {@code generate.py} emits,
 * against a live grug state.
 *
 * <p>Every other test of #890 drives the Java side without the wrapper, so a wrong method
 * descriptor, a state pointer that is not the one the wrapper was called with, or a dropped {@code
 * grug_set_runtime_error} would leave them green. Here a compiled script calls a host function that
 * throws a real Java exception, and the assertions are on what the state and the test runner did
 * with it.
 *
 * <p>The exception is real without a game: {@code Test.get_client_level()} asks the adapter for the
 * level, and this JVM has none, so the adapter answers null and its {@code getSide()} throws. That
 * is a Java exception inside a host function, which is exactly the defect a host function can have.
 */
class GrugHostFunctionExceptionTest {

    /** A test whose tick calls the host function that throws. */
    private static final String THROWING_TEST =
            "export tick(ctx: TestTickContext) {\n"
                    + "    level: Level = Test.get_client_level()\n"
                    + "}\n";

    /** A fixture that throws the same way, which is what a test's block entity can do. */
    private static final String THROWING_FIXTURE =
            "export tick(ctx: TestTickContext) {\n"
                    + "    level: Level = Test.get_client_level()\n"
                    + "}\n";

    /** A test that keeps asking for ticks, so a fixture can run between two of them. */
    private static final String STAYING_TEST =
            "export tick(ctx: TestTickContext) {\n" + "    ctx.not_done()\n" + "}\n";

    /** One state for the class, because booting one is the expensive part. */
    @TempDir static Path mods;

    private static long savedStatePtr;
    private static Object savedAdapter;

    @BeforeAll
    static void bootAStateWithAModThatThrows() throws Exception {
        savedStatePtr = Grug.statePtr;
        savedAdapter = adapterField().get(null);
        adapterField().set(null, adapterWithoutALevel());

        writeMod("throws", "a-Test.grug", THROWING_TEST);
        writeMod("fixture", "b-Test.grug", THROWING_FIXTURE);
        writeMod("stays", "c-Test.grug", STAYING_TEST);
        Grug.init(modApiJson(), mods.toFile());
        assertNotEquals(0, Grug.statePtr, "the grug state did not come up");
    }

    /** The API file the repository keeps at its root, one level above this module. */
    private static File modApiJson() {
        File atTheWorkingDirectory = new File("../mod_api.json");
        return atTheWorkingDirectory.isFile() ? atTheWorkingDirectory : new File("mod_api.json");
    }

    @AfterAll
    static void restoreTheStateAndTheAdapter() throws Exception {
        adapterField().set(null, savedAdapter);
        Grug.statePtr = savedStatePtr;
    }

    @BeforeEach
    void forgetWhatTheLastTestReported() {
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
        GrugEntities.endTestEntities();
    }

    @Test
    void aHostFunctionThatThrowsStopsTheScriptAndIsReportedByName() {
        // The wrapper used to clear the exception and answer a zero value, so the script carried on
        // and the call answered true. The state has to stop it instead, and the report has to name
        // the function the bridge called and the exception it threw (#890).
        long fileId = compile("a-Test.grug");
        long entity = GrugEntities.createTestEntity(fileId);
        long tick = Grug.getExportFnId("Test", "tick");

        assertFalse(GrugTestState.callTick(entity, tick), "the script carried on after the throw");

        String reported = runtimeErrors();
        assertTrue(reported.contains("HostFunctions.Test_get_client_level"), reported);
        assertTrue(reported.contains("java.lang.NullPointerException"), reported);
        assertTrue(reported.contains("defect in grug"), reported);
        // The record the runner fails the run for is the same report, so a throw with no test call
        // to abort cannot be lost.
        assertTrue(GrugErrors.takeHostFunctionException().contains("Test_get_client_level"));
    }

    @Test
    void theRunnerFailsTheTestWhoseOwnCallThrew() {
        long fileId = compile("a-Test.grug");
        GrugTestRunner runner =
                new GrugTestRunner(
                        Map.of("throws/tests/a-Test.grug", fileId), List.of(), nativeOps());

        String printed = capture(() -> runToEnd(runner));

        assertTrue(printed.contains("[GRUG CI] FAIL throws/tests/a-Test.grug"), printed);
        // The report itself goes to the chat queue, which a loader logs; the FAIL line names the
        // test whose call it was.
        assertTrue(runtimeErrors().contains("Test_get_client_level"), runtimeErrors());
        assertFalse(printed.contains("[GRUG CI] PASS"), printed);
        assertFalse(printed.contains("TESTS PASSED"), printed);
    }

    @Test
    void theRunnerFailsTheRunUnderTheTestAFixtureThrewDuring() {
        // A test's fixture ticks between two of the test's own calls, so its throw has no call of
        // the test's to abort. Before the fix the report went to the log and the run ended green.
        long testFile = compile("c-Test.grug");
        long fixtureFile = compile("b-Test.grug");
        GrugTestRunner runner =
                new GrugTestRunner(
                        Map.of("stays/tests/c-Test.grug", testFile), List.of(), nativeOps());

        runner.tick(null);
        assertFalse(runner.isFinished(), "the test has to still be running for this case");

        long fixture = GrugEntities.createTestEntity(fixtureFile);
        assertFalse(
                GrugTestState.callTick(fixture, Grug.getExportFnId("Test", "tick")),
                "the fixture's script carried on after the throw");

        String printed = capture(() -> runner.tick(null));

        assertTrue(printed.contains("[GRUG CI] FAIL stays/tests/c-Test.grug"), printed);
        assertTrue(printed.contains("Test_get_client_level"), printed);
        assertFalse(printed.contains("TESTS PASSED"), printed);
    }

    /** Writes one mod with a single script, the way a loader's mods directory holds them. */
    private static void writeMod(String mod, String file, String source) throws IOException {
        Path code = Files.createDirectories(mods.resolve(mod).resolve("code"));
        Files.write(code.resolve(file), source.getBytes(StandardCharsets.UTF_8));
    }

    /** The file id of a script this test wrote, once every script has compiled. */
    private static long compile(String file) {
        for (FileInfo info : Grug.compileAllFiles()) {
            if (info.fileName().equals(file)) {
                assertTrue(
                        info.errorString() == null,
                        info.fileName() + " did not compile: " + info.errorString());
                return info.fileId();
            }
        }
        throw new AssertionError(file + " was not compiled");
    }

    private static GrugTestRunner.TestEntityOps nativeOps() {
        return new GrugTestRunner.TestEntityOps() {
            @Override
            public long createEntity(long fileId) {
                return GrugEntities.createTestEntity(fileId);
            }

            @Override
            public long getExportFnId(String entityType, String fnName) {
                return Grug.getExportFnId(entityType, fnName);
            }

            @Override
            public boolean callExportFn(long entityHandle, long exportFnId) {
                return GrugTestState.callTick(entityHandle, exportFnId);
            }

            @Override
            public boolean callInitFn(long entityHandle, long exportFnId) {
                return GrugTestState.callInit(entityHandle, exportFnId);
            }

            @Override
            public void destroyEntity(long entityHandle) {
                Grug.destroyEntity(entityHandle);
            }
        };
    }

    private static String runtimeErrors() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            return String.join("\n", GrugErrors.runtimeErrorQueue);
        }
    }

    private static String capture(Runnable body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(saved);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void runToEnd(GrugTestRunner runner) {
        for (int i = 0;
                i < GrugTestRunner.DEFAULT_MAX_TEST_TICKS + 5 && !runner.isFinished();
                i++) {
            runner.tick(null);
        }
    }

    /**
     * An adapter with no world: its level is null, and asking a null side what it is throws, which
     * is the Java exception the host function this test is about has to throw.
     */
    private static Object adapterWithoutALevel() {
        return Proxy.newProxyInstance(
                GrugHostFunctionExceptionTest.class.getClassLoader(),
                new Class<?>[] {ModLoaderAdapter.class},
                (proxy, method, args) -> null);
    }

    private static Field adapterField() throws NoSuchFieldException {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        return field;
    }
}
