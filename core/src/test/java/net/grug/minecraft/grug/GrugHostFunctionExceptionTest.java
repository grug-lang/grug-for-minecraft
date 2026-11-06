package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    /** The error a host-side write reports when no grug call is there to fail. */
    private static final String NO_EXPORT_MESSAGE =
            "The world refused the block at 0, 128, 0, and no grug call made that write";

    /** A Block whose Java-invoked {@code use} export throws through a host function. */
    private static final String THROWING_USE =
            "export use(ctx: BlockUseContext, world: Level, x: number, y: number, z: number,"
                    + " player: Player) {\n"
                    + "    level: Level = Test.get_client_level()\n"
                    + "}\n";

    /** A Block whose member scope throws through a host function when its entity is created. */
    private static final String THROWING_MEMBER = "level: Level = Test.get_client_level()\n";

    /** A Block whose member scope is harmless, so its entity can be created and tracked. */
    private static final String HARMLESS_MEMBER = "count: number = 0\n";

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
        writeMod("use-block", "u-Block.grug", THROWING_USE);
        writeMod("init-block", "i-Block.grug", THROWING_MEMBER);
        writeMod("recompile-block", "r-Block.grug", HARMLESS_MEMBER);
        writeMod("reload-block", "v-Block.grug", HARMLESS_MEMBER);
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
        // A unit-test runner does not own reports; the tests that need a record set this
        // themselves,
        // and the rest start from no run, so the order these tests run in does not matter.
        GrugTestState.testRunActive = false;
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

    @Test
    void aHostFunctionErrorWithNoRunIsReportedInsteadOfAborting() {
        // The room build writes blocks from the server tick, between two calls, where grug-rs has
        // pushed no frame: its native report used to panic on the empty call stack and abort the
        // process (#1134). With no test run to fail, the error is a bounded defect of the write
        // that reported it: the game keeps running, the log and chat get the message, and nothing
        // is left in the record for a later run's first test to inherit.
        String printed =
                capture(() -> Grug.hostFunctionErrorHappened(Grug.statePtr, NO_EXPORT_MESSAGE));

        assertTrue(printed.contains("[GRUG CI] FAIL " + NO_EXPORT_MESSAGE), printed);
        assertTrue(runtimeErrors().contains(NO_EXPORT_MESSAGE), runtimeErrors());
        assertNull(GrugErrors.takeHostFunctionException());
    }

    @Test
    void aRefusedRunOwnsNothingSoNothingIsRecordedForIt() {
        // The runner that refused to start owns no reports: recording one for it would hold it
        // until a test of a run that never starts, and the next run would inherit it.
        GrugTestState.runStarting(true);
        assertFalse(GrugTestState.testRunActive);

        GrugTestState.runStarting(false);
        assertTrue(GrugTestState.testRunActive);
    }

    @Test
    void aHostFunctionErrorWithNoRunAndNoAdapterIsStillReported() throws Exception {
        // The state can be up before a loader has set an adapter, and the report must not need one
        // to reach the log and chat.
        Field field = adapterField();
        Object installed = field.get(null);
        field.set(null, null);
        try {
            String printed =
                    capture(() -> Grug.hostFunctionErrorHappened(Grug.statePtr, NO_EXPORT_MESSAGE));

            assertTrue(printed.contains("[GRUG CI] FAIL " + NO_EXPORT_MESSAGE), printed);
            assertTrue(runtimeErrors().contains(NO_EXPORT_MESSAGE), runtimeErrors());
        } finally {
            field.set(null, installed);
        }
    }

    @Test
    void aReportMadeBeforeARunDoesNotFailItsFirstTest() {
        // The no-run case is not recorded, so a session that reported before the run was started
        // cannot hand the run's first test a record it did not make.
        capture(() -> Grug.hostFunctionErrorHappened(Grug.statePtr, NO_EXPORT_MESSAGE));

        long testFile = compile("c-Test.grug");
        GrugTestRunner runner =
                new GrugTestRunner(
                        Map.of("stays/tests/c-Test.grug", testFile), List.of(), nativeOps());

        String first = capture(() -> runner.tick(null));

        assertTrue(first.contains("[GRUG CI] Executing test: stays/tests/c-Test.grug"), first);
        assertFalse(first.contains("[GRUG CI] FAIL"), first);
    }

    @Test
    void theRunnerFailsTheTestTheRoomBuildReportedUnderWithNoExport() {
        // The other end of the same path: a record made between two calls of a running test is
        // drained by the runner's next tick, which fails that test by name.
        long testFile = compile("c-Test.grug");
        GrugTestRunner runner =
                new GrugTestRunner(
                        Map.of("stays/tests/c-Test.grug", testFile), List.of(), nativeOps());
        runner.tick(null);
        assertFalse(runner.isFinished(), "the test has to still be running for this case");
        // The loader's own runner sets this while it runs; a unit-test runner leaves it to its
        // test, so the run has to own reports here for the record path to be the one taken.
        GrugTestState.testRunActive = true;

        Grug.hostFunctionErrorHappened(Grug.statePtr, NO_EXPORT_MESSAGE);

        String printed = capture(() -> runner.tick(null));

        assertTrue(printed.contains("[GRUG CI] FAIL stays/tests/c-Test.grug"), printed);
        assertTrue(printed.contains(NO_EXPORT_MESSAGE), printed);
        assertFalse(printed.contains("TESTS PASSED"), printed);
    }

    @Test
    void aHostFunctionErrorWithNoExportToFailDoesNotAbortTheProcess() throws Exception {
        // The call above proves the record, but a build without the guard aborts the whole test
        // JVM there, so the exit status is asserted in a JVM of its own: it answers 0 with the
        // record, where the unguarded path answers the abort's status and grug-rs's panic.
        ProcessBuilder builder =
                new ProcessBuilder(
                        Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        GrugHostFunctionNoExportMain.class.getName(),
                        modApiJson().getAbsolutePath(),
                        mods.toAbsolutePath().toString(),
                        NO_EXPORT_MESSAGE);
        builder.redirectErrorStream(true);
        Process child = builder.start();
        assertTrue(child.waitFor(120, TimeUnit.SECONDS), "the child JVM did not end");
        String printed = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, child.exitValue(), printed);
        // No run is active in the child either, so it reports the error to the log and chat and
        // leaves nothing in the record.
        assertTrue(printed.contains("[GRUG CI] FAIL " + NO_EXPORT_MESSAGE), printed);
        assertTrue(
                printed.contains(GrugHostFunctionNoExportMain.RECORDED_PREFIX + "null"), printed);
    }

    @Test
    void aHostErrorInsideAJavaInvokedExportStillFailsTheCall() {
        // Block.use is reached through an ExportFns wrapper, whose native side pushes the export's
        // frame even though Grug.callExportFn is not the entry. The thread has to be marked there
        // too, or the throw would be recorded, the script would carry on, and the click would be
        // reported as handled (#1134).
        long block = Grug.createEntity(compile("u-Block.grug"));
        assertNotEquals(0, block, "the block entity was not created");

        assertFalse(
                GrugBlockUse.use(block, 0, 0, 0, 0, 0),
                "the script carried on after the throw in its use export");
        assertTrue(runtimeErrors().contains("Test_get_client_level"), runtimeErrors());
    }

    @Test
    void aHostErrorInAMemberInitializerStillFailsEntityCreation() {
        // Creating an entity runs its member scope in an initializer on grug's call stack. With the
        // thread marked, the throw has to fail the creation; unmarked, it would be recorded and the
        // entity would come up with a member the host never answered (#1134).
        assertEquals(0, Grug.createEntity(compile("i-Block.grug")));
        assertTrue(runtimeErrors().contains("Test_get_client_level"), runtimeErrors());
    }

    @Test
    void aRecordWaitingWhenTheTestFailsIsNotDropped() {
        // A client thread reports while the server's call is suspended, which is a record with no
        // call of its own on that thread. Here the suspended call resumes and fails for its own
        // reason in the same tick, so fail() ends the run before the next tick can take the
        // record: it still has to name the host function it came from (#1134).
        long testFile = compile("c-Test.grug");
        boolean[] reportOnNextCall = {false};
        GrugTestRunner.TestEntityOps ops =
                new GrugTestRunner.TestEntityOps() {
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
                        if (reportOnNextCall[0]) {
                            reportOnNextCall[0] = false;
                            Grug.hostFunctionErrorHappened(Grug.statePtr, NO_EXPORT_MESSAGE);
                            return false;
                        }
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
        GrugTestRunner runner =
                new GrugTestRunner(Map.of("stays/tests/c-Test.grug", testFile), List.of(), ops);
        runner.tick(null);
        assertFalse(runner.isFinished(), "the test has to still be running for this case");
        // The loader's own runner sets this while it runs; a unit-test runner leaves it to its
        // test, so the run has to own reports here for the record path to be the one taken.
        GrugTestState.testRunActive = true;

        reportOnNextCall[0] = true;
        String printed = capture(() -> runner.tick(null));

        assertTrue(printed.contains("[GRUG CI] FAIL stays/tests/c-Test.grug"), printed);
        assertTrue(printed.contains(NO_EXPORT_MESSAGE), printed);
    }

    @Test
    void aRecordLeftWhenTheRunEndsFailsTheRunInsteadOfPassing() {
        // The record can arrive in the same tick as the last test's end, when no next tick is left
        // to drain it. The run must not end green for it, and the message belongs to the test that
        // just ended.
        long testFile = compile("c-Test.grug");
        GrugTestRunner runner =
                new GrugTestRunner(
                        Map.of("stays/tests/c-Test.grug", testFile),
                        List.of(),
                        completingOps(
                                () ->
                                        Grug.hostFunctionErrorHappened(
                                                Grug.statePtr, NO_EXPORT_MESSAGE)));
        GrugTestState.testRunActive = true;

        // The test passes in the first tick, which is also where the error was reported, and the
        // run finishes on the next one: the record has to fail it there rather than be lost.
        runner.tick(null);
        String printed = capture(() -> runner.tick(null));

        assertTrue(printed.contains("[GRUG CI] FAIL stays/tests/c-Test.grug"), printed);
        assertFalse(printed.contains("TESTS PASSED"), printed);
    }

    @Test
    void aReportAfterTheLastTestPassedIsReportedNotHeldForALaterRun() {
        // A run owns nothing once its last test has passed, though it finishes a tick later: a
        // report in between is reported to the log and chat, not stored where the next run's first
        // test would inherit it.
        long testFile = compile("c-Test.grug");
        GrugTestRunner runner =
                new GrugTestRunner(
                        Map.of("stays/tests/c-Test.grug", testFile),
                        List.of(),
                        completingOps(() -> {}));
        GrugTestState.testRunActive = true;
        runner.tick(null);
        assertFalse(runner.isFinished(), "the run finishes on its next tick");

        String printed =
                capture(() -> Grug.hostFunctionErrorHappened(Grug.statePtr, NO_EXPORT_MESSAGE));

        assertTrue(printed.contains("[GRUG CI] FAIL " + NO_EXPORT_MESSAGE), printed);
        assertNull(GrugErrors.takeHostFunctionException());
    }

    /**
     * The runner's entity operations for a test that its first tick completes: the tick_any_side
     * probe misses, then the tick runs {@code onTick} and answers that it completed.
     */
    private static GrugTestRunner.TestEntityOps completingOps(Runnable onTick) {
        int[] calls = {0};
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
                if (++calls[0] == 2) {
                    onTick.run();
                    return true;
                }
                return false;
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

    @Test
    void aSecondCompileFailsTheInitializerOfAnEntityItAlreadyTracks() throws IOException {
        // A compile re-initializes the entities of a file it already tracks, which pushes a frame
        // on
        // grug's call stack. Marked, the throw fails that initializer and reaches the runtime error
        // channel; unmarked, it was only recorded and the entity kept a member the host never
        // answered (#1134).
        long block = Grug.createEntity(compile("r-Block.grug"));
        assertNotEquals(0, block, "the block entity was not created");
        // A run owns reports here, so an unmarked call would leave the message in the record
        // instead of raising it: that is the difference this test tells.
        GrugTestState.testRunActive = true;
        rewrite("recompile-block", "r-Block.grug", THROWING_MEMBER);

        Grug.compileAllFiles();

        assertTrue(runtimeErrors().contains("Test_get_client_level"), runtimeErrors());
        Grug.destroyEntity(block);
    }

    @Test
    void aReloadFailsTheInitializerOfAnEntityItRecompiles() throws Exception {
        // The watcher's update recompiles a changed file and re-initializes its entities through
        // the same native entry, and that call carries the same mark.
        long block = Grug.createEntity(compile("v-Block.grug"));
        assertNotEquals(0, block, "the block entity was not created");
        // A run owns reports here, so an unmarked call would leave the message in the record
        // instead of raising it: that is the difference this test tells.
        GrugTestState.testRunActive = true;
        rewrite("reload-block", "v-Block.grug", THROWING_MEMBER);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!runtimeErrors().contains("Test_get_client_level") && System.nanoTime() < deadline) {
            GrugModsWatcher.update(error -> {});
            Thread.sleep(50);
        }

        assertTrue(runtimeErrors().contains("Test_get_client_level"), runtimeErrors());
        Grug.destroyEntity(block);
    }

    /** Writes one mod with a single script, the way a loader's mods directory holds them. */
    private static void writeMod(String mod, String file, String source) throws IOException {
        Path code = Files.createDirectories(mods.resolve(mod).resolve("code"));
        Files.write(code.resolve(file), source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Rewrites one script of a mod this test wrote, the way an author's edit changes it. The move
     * keeps the poller from reading the file half written, which would record a compile error for
     * content that never existed.
     */
    private static void rewrite(String mod, String file, String source) throws IOException {
        Path target = mods.resolve(mod).resolve("code").resolve(file);
        Path temporary = target.resolveSibling(file + ".tmp");
        Files.write(temporary, source.getBytes(StandardCharsets.UTF_8));
        Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
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
