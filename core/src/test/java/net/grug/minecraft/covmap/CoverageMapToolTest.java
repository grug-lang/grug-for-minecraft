package net.grug.minecraft.covmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the coverage map tool against class files it writes itself (#786): the map records what
 * each test covered on each loader, keyed by class and probe, and reconstruct applies a recorded
 * map to a run's classes, failing the run when a class it names changed under it.
 */
class CoverageMapToolTest {

    private static final String SAMPLE = "net/grug/covmaptest/Sample";

    /** A synthetic class, the shape of the marker classes the core build holds. */
    private static final String SYNTHETIC = "net/grug/covmaptest/Sample$1";

    /** A class file holding one public method with a branch, so it has more than one probe. */
    private static byte[] classNamed(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hit", "(I)V", null, null);
        mv.visitCode();
        Label branch = new Label();
        Label merge = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFGT, branch);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, merge);
        mv.visitLabel(branch);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitLabel(merge);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The same class with one more instruction, so a different id, for the changed-class case. */
    private static byte[] classChangedNamed(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hit", "(I)V", null, null);
        mv.visitCode();
        Label branch = new Label();
        Label merge = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFGT, branch);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, merge);
        mv.visitLabel(branch);
        mv.visitInsn(Opcodes.ICONST_M1);
        mv.visitLabel(merge);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, name, "never", "()V", false);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] sampleClass() {
        return classNamed(SAMPLE);
    }

    private static byte[] sampleClassChanged() {
        return classChangedNamed(SAMPLE);
    }

    /**
     * A synthetic class file with no members, the shape of the marker classes javac synthesizes
     * when a class calls a nested class's private constructor, and which the core build holds: the
     * analysis does not record it, so the map does not either.
     */
    private static byte[] syntheticClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V1_8,
                Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC,
                SYNTHETIC,
                null,
                "java/lang/Object",
                null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * How many probes JaCoCo's own flow analysis assigns to the class, the length every exec vector
     * of it has. The internal classes are JaCoCo core's own, the same ones the tool analyses with,
     * and the count is how the analysis is defined.
     */
    private static int probeCountOf(String name, byte[] classBytes) throws Exception {
        int[] total = {0};
        var coverage = new org.jacoco.core.internal.analysis.ClassCoverageImpl(name, 0L, false);
        var analyzer =
                new org.jacoco.core.internal.analysis.ClassAnalyzer(
                        coverage, null, new org.jacoco.core.internal.analysis.StringPool()) {
                    @Override
                    public void visitTotalProbeCount(int totalProbeCount) {
                        total[0] = totalProbeCount;
                    }
                };
        new ClassReader(classBytes)
                .accept(new org.jacoco.core.internal.flow.ClassProbesAdapter(analyzer, false), 0);
        return total[0];
    }

    /** Writes the class and returns the directory that holds it by class name. */
    private static Path classesDir(Path tmp, byte[] classBytes) throws Exception {
        return classesDir(tmp, SAMPLE, classBytes);
    }

    /** Writes the named class file under the shared classes directory and returns the directory. */
    private static Path classesDir(Path tmp, String name, byte[] classBytes) throws Exception {
        return underDir(tmp.resolve("classes"), name, classBytes);
    }

    /**
     * Writes a loader's own class under a directory of its own and returns it: a loader compiles
     * classes core does not hold, and two loaders may compile different code under one name.
     */
    private static Path loaderClassesDir(Path tmp, String dir, String name, byte[] classBytes)
            throws Exception {
        return underDir(tmp.resolve(dir), name, classBytes);
    }

    /** A loader's classes directory holding one class no exec of the test hits. */
    private static Path quietLoaderClasses(Path tmp, String dir) throws Exception {
        return loaderClassesDir(
                tmp, dir, "net/grug/covmaptest/Unused", classNamed("net/grug/covmaptest/Unused"));
    }

    private static Path underDir(Path root, String name, byte[] classBytes) throws Exception {
        Path file = root.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, classBytes);
        return root;
    }

    /** An exec file named for the flattened test path, holding one class's probes. */
    private static void writeExec(
            Path dir, String fileName, String class_, long id, boolean[] probes) throws Exception {
        Files.createDirectories(dir);
        ExecutionDataStore store = new ExecutionDataStore();
        store.put(new ExecutionData(id, class_, probes));
        try (var out = Files.newOutputStream(dir.resolve(fileName))) {
            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            for (ExecutionData data : store.getContents()) {
                writer.visitClassExecution(data);
            }
            writer.flush();
        }
    }

    /**
     * The exec file as the agent's {@code getExecutionData} writes it: the session header first,
     * then the classes. Every per-test segment the runner cuts is such a file.
     */
    private static void writeAgentDump(
            Path dir, String fileName, String class_, long id, boolean[] probes) throws Exception {
        Files.createDirectories(dir);
        ExecutionDataStore store = new ExecutionDataStore();
        store.put(new ExecutionData(id, class_, probes));
        try (var out = Files.newOutputStream(dir.resolve(fileName))) {
            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            writer.visitSessionInfo(new SessionInfo("the-test", 1L, 2L));
            for (ExecutionData data : store.getContents()) {
                writer.visitClassExecution(data);
            }
            writer.flush();
        }
    }

    /** An exec file the tool wrote, read back as the gate would. */
    private static ExecutionDataStore readExec(Path exec) throws Exception {
        ExecutionDataStore store = new ExecutionDataStore();
        try (InputStream in = Files.newInputStream(exec)) {
            ExecutionDataReader reader = new ExecutionDataReader(in);
            reader.setExecutionDataVisitor(store::put);
            while (reader.read()) {
                // the store took the entry
            }
        }
        return store;
    }

    /** The entry of one class in an exec the tool wrote. */
    private static ExecutionData dataNamed(ExecutionDataStore store, String name) {
        for (ExecutionData data : store.getContents()) {
            if (data.getName().equals(name)) {
                return data;
            }
        }
        throw new AssertionError("no exec data for " + name + ": " + store.getContents());
    }

    private static boolean[] probes(int count, int... hit) {
        boolean[] out = new boolean[count];
        for (int p : hit) {
            out[p] = true;
        }
        return out;
    }

    /** The build command: the core classes, then name/classes/execs per loader, in that order. */
    private static String[] buildArgs(Path coreClasses, Path map, String... loadersAndDirs) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "build",
                                "--classes",
                                coreClasses.toString(),
                                "--commit",
                                "cafebabe",
                                "--out",
                                map.toString()));
        for (int i = 0; i < loadersAndDirs.length; i += 3) {
            args.add("--loader");
            args.add(loadersAndDirs[i]);
            args.add(loadersAndDirs[i + 1]);
            args.add(loadersAndDirs[i + 2]);
        }
        return args.toArray(String[]::new);
    }

    /** The reconstruct command: the core classes and the tests, then name/classes per loader. */
    private static String[] reconstructArgs(
            Path map, Path coreClasses, Path tests, Path out, String... loadersAndClasses) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "reconstruct",
                                "--map",
                                map.toString(),
                                "--classes",
                                coreClasses.toString(),
                                "--tests",
                                tests.toString(),
                                "--out-dir",
                                out.toString()));
        for (int i = 0; i < loadersAndClasses.length; i += 2) {
            args.add("--loader");
            args.add(loadersAndClasses[i]);
            args.add(loadersAndClasses[i + 1]);
        }
        return args.toArray(String[]::new);
    }

    @Test
    void theTestPathFlatteningIsABijection() {
        assertEquals(
                "coverage/tests/a-Test.grug",
                CoverageMapTool.testPathOf("coverage.tests.a-Test.grug"));
        assertEquals(
                "coverage.tests.a-Test.grug",
                CoverageMapTool.flattenedOf("coverage/tests/a-Test.grug"));
    }

    @Test
    void buildRecordsWhatEachTestCoveredPerLoader(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(SAMPLE, classBytes);
        Path coreClasses = classesDir(tmp, classBytes);

        // Loader A's own class, which only its run hits: a loader's classes are recorded under
        // that loader, never as core's, since each loader's report counts only its own classes.
        String loaderClass = "net/grug/covmaptest/LoaderA";
        byte[] loaderClassBytes = classNamed(loaderClass);
        long loaderId = CoverageMapTool.classId(loaderClassBytes);
        int loaderProbes = probeCountOf(loaderClass, loaderClassBytes);
        Path classesA = loaderClassesDir(tmp, "loader-a/classes", loaderClass, loaderClassBytes);
        Path classesB = quietLoaderClasses(tmp, "loader-b/classes");

        // Two segments of a-Test on A union, since a repeats dispatch cuts the suite more than
        // once, and B's own run keeps what B hit: the recorded coverage is per loader.
        Path execA = tmp.resolve("exec-a");
        Path execB = tmp.resolve("exec-b");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));
        writeExec(execA, "001-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 1));
        writeExec(
                execA,
                "002-coverage.tests.b-Test.grug.exec",
                SAMPLE,
                id,
                probes(probeCount, 0, probeCount - 1));
        writeExec(
                execA,
                "003-coverage.tests.a-Test.grug.exec",
                loaderClass,
                loaderId,
                probes(loaderProbes, 0));
        writeExec(execA, "004-boot.exec", SAMPLE, id, probes(probeCount, 1));
        writeExec(execA, "005-jei.tests.a-Test.grug.dropped", SAMPLE, id, probes(probeCount, 2));
        writeExec(execB, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 1));
        writeExec(
                execB, "001-loader.tests.a-Test.grug.exec", "loader/Other", 0x1234L, probes(2, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "loader-a",
                                classesA.toString(),
                                execA.toString(),
                                "loader-b",
                                classesB.toString(),
                                execB.toString())));

        JsonObject tree = JsonParser.parseString(Files.readString(map)).getAsJsonObject();
        assertEquals(2L, tree.get("version").getAsLong());
        assertEquals("cafebabe", tree.get("commit").getAsString());
        JsonElement classInfo = tree.getAsJsonObject("classes").get(SAMPLE);
        assertEquals(
                Long.toUnsignedString(id), classInfo.getAsJsonObject().get("crc64").getAsString());
        assertEquals(probeCount, classInfo.getAsJsonObject().get("probes").getAsInt());
        // A loader's own class is in that loader's section, never in the core one.
        assertFalse(tree.getAsJsonObject("classes").has(loaderClass));
        JsonObject loaderA = tree.getAsJsonObject("loaders").getAsJsonObject("loader-a");
        assertEquals(
                Long.toUnsignedString(loaderId),
                loaderA.getAsJsonObject("classes")
                        .getAsJsonObject(loaderClass)
                        .get("crc64")
                        .getAsString());

        JsonObject aA =
                loaderA.getAsJsonObject("tests").getAsJsonObject("coverage/tests/a-Test.grug");
        assertEquals(
                List.of(0, 1),
                probeList(aA.get(SAMPLE).getAsJsonObject().get("probes").getAsJsonArray()));
        assertEquals(
                List.of(0),
                probeList(aA.get(loaderClass).getAsJsonObject().get("probes").getAsJsonArray()));
        assertTrue(
                aA.get(SAMPLE).getAsJsonObject().get("methods").toString().contains("hit"),
                "the executed method is recorded: " + aA);
        JsonObject bA =
                loaderA.getAsJsonObject("tests").getAsJsonObject("coverage/tests/b-Test.grug");
        assertEquals(
                List.of(0, probeCount - 1),
                probeList(bA.get(SAMPLE).getAsJsonObject().get("probes").getAsJsonArray()));

        JsonObject loaderB = tree.getAsJsonObject("loaders").getAsJsonObject("loader-b");
        JsonObject aB =
                loaderB.getAsJsonObject("tests").getAsJsonObject("coverage/tests/a-Test.grug");
        assertEquals(
                List.of(1),
                probeList(aB.get(SAMPLE).getAsJsonObject().get("probes").getAsJsonArray()));
        assertFalse(aB.has(loaderClass), "loader A's class is not in loader B's coverage");
        // The class the run's classes do not hold, and the dropped and boot segments, are not
        // recorded anywhere.
        assertFalse(loaderB.getAsJsonObject("tests").has("loader/tests/a-Test.grug"));
        assertFalse(loaderB.getAsJsonObject("tests").has("boot"));
    }

    private static List<Integer> probeList(JsonArray array) {
        List<Integer> out = new ArrayList<>();
        for (JsonElement p : array) {
            out.add(p.getAsInt());
        }
        return out;
    }

    @Test
    void buildRecordsASegmentNumberedAbove999(@TempDir Path tmp) throws Exception {
        // The segment number is written with %03d, which widens rather than truncating past 999:
        // a repeats dispatch writes 1000-... and later, and a reader that demanded three digits
        // would drop every test after the thousandth segment without saying so.
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(SAMPLE, classBytes);
        Path coreClasses = classesDir(tmp, classBytes);
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("exec");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));
        writeExec(execA, "1000-coverage.tests.b-Test.grug.exec", SAMPLE, id, probes(probeCount, 1));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        JsonObject tests =
                JsonParser.parseString(Files.readString(map))
                        .getAsJsonObject()
                        .getAsJsonObject("loaders")
                        .getAsJsonObject("test-loader")
                        .getAsJsonObject("tests");
        assertTrue(tests.has("coverage/tests/a-Test.grug"), tests.toString());
        assertTrue(tests.has("coverage/tests/b-Test.grug"), tests.toString());
    }

    @Test
    void buildReadsTheSessionHeaderTheAgentStampsOnEveryDump(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(SAMPLE, classBytes);
        Path coreClasses = classesDir(tmp, classBytes);
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("execA");
        writeAgentDump(
                execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        JsonElement a =
                JsonParser.parseString(Files.readString(map))
                        .getAsJsonObject()
                        .getAsJsonObject("loaders")
                        .getAsJsonObject("test-loader")
                        .getAsJsonObject("tests")
                        .get("coverage/tests/a-Test.grug");
        assertEquals(
                List.of(0),
                probeList(
                        a.getAsJsonObject()
                                .get(SAMPLE)
                                .getAsJsonObject()
                                .get("probes")
                                .getAsJsonArray()));
    }

    @Test
    void buildRecordsNothingForAClassTheAnalysisDoesNotRecord(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(SAMPLE, classBytes);
        // The analysis records nothing for a synthetic class, and the id of one is the error
        // that says so: the map records only what the analysis records.
        assertNull(CoverageMapTool.classIdIfAnalyzed(syntheticClass()));
        assertThrows(IOException.class, () -> CoverageMapTool.classId(syntheticClass()));
        Path coreClasses = classesDir(tmp, classBytes);
        classesDir(tmp, SYNTHETIC, syntheticClass());
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        // The synthetic class is among the run's classes, but not in the map: build used to die
        // on it before it learned the analysis records nothing for it.
        JsonObject tree = JsonParser.parseString(Files.readString(map)).getAsJsonObject();
        assertEquals(1, tree.getAsJsonObject("classes").size());
        assertFalse(tree.getAsJsonObject("classes").has(SYNTHETIC));
    }

    @Test
    void reconstructWritesTheRecordedProbesForTheNamedTest(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(SAMPLE, classBytes);
        Path coreClasses = classesDir(tmp, classBytes);
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        assertEquals(
                0,
                CoverageMapTool.run(
                        reconstructArgs(
                                map,
                                coreClasses,
                                tests,
                                outDir,
                                "test-loader",
                                classes.toString())));

        Path exec = outDir.resolve("test-loader").resolve("coverage.tests.a-Test.grug.exec");
        assertTrue(Files.isRegularFile(exec), "the carried-over test's exec was written");
        ExecutionDataStore store = readExec(exec);
        assertEquals(1, store.getContents().size());
        ExecutionData data = store.getContents().iterator().next();
        assertEquals(id, data.getId());
        assertEquals(SAMPLE, data.getName());
        assertEquals(probeCount, data.getProbes().length);
        // The one recorded probe is set, and every other probe the class has is not.
        assertTrue(data.getProbes()[0]);
        for (int p = 1; p < probeCount; p++) {
            assertTrue(!data.getProbes()[p]);
        }
    }

    @Test
    void reconstructSkipsAClassTheAnalysisDoesNotRecord(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(SAMPLE, classBytes);
        // The map is built with the synthetic class among the run's classes, so it holds no
        // entry for it, and this run's classes hold it too.
        Path coreClasses = classesDir(tmp, classBytes);
        classesDir(tmp, SYNTHETIC, syntheticClass());
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        assertEquals(
                0,
                CoverageMapTool.run(
                        reconstructArgs(
                                map,
                                coreClasses,
                                tests,
                                outDir,
                                "test-loader",
                                classes.toString())));

        // The carried-over exec holds the one recorded class with its recorded probe, and
        // nothing of the synthetic class the map does not record.
        ExecutionDataStore store =
                readExec(outDir.resolve("test-loader").resolve("coverage.tests.a-Test.grug.exec"));
        assertEquals(1, store.getContents().size());
        ExecutionData data = store.getContents().iterator().next();
        assertEquals(id, data.getId());
        assertEquals(SAMPLE, data.getName());
        assertTrue(data.getProbes()[0]);
        for (int p = 1; p < probeCount; p++) {
            assertTrue(!data.getProbes()[p]);
        }
    }

    @Test
    void reconstructRefusesAClassThatChangedUnderTheMap(@TempDir Path tmp) throws Exception {
        int probeCount = probeCountOf(SAMPLE, sampleClass());
        Path classesAtMapTime = classesDir(tmp.resolve("at-map-time"), sampleClass());
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("execA");
        writeExec(
                execA,
                "000-coverage.tests.a-Test.grug.exec",
                SAMPLE,
                CoverageMapTool.classId(sampleClass()),
                probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                classesAtMapTime,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        // This run's class is a changed one: the same method with more in it, a different id.
        Path classesAtRunTime = classesDir(tmp.resolve("at-run-time"), sampleClassChanged());
        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        assertEquals(
                1,
                CoverageMapTool.run(
                        reconstructArgs(
                                map,
                                classesAtRunTime,
                                tests,
                                outDir,
                                "test-loader",
                                classes.toString())));
    }

    @Test
    void reconstructRefusesAClassOfALoaderThatChangedUnderTheMap(@TempDir Path tmp)
            throws Exception {
        String adapter = "net/grug/covmaptest/Adapter";
        Path coreClasses = classesDir(tmp, sampleClass());
        Path classesAtMapTime =
                loaderClassesDir(tmp, "loader-at-map-time", adapter, classNamed(adapter));
        Path execA = tmp.resolve("execA");
        writeExec(
                execA,
                "000-coverage.tests.a-Test.grug.exec",
                adapter,
                CoverageMapTool.classId(classNamed(adapter)),
                probes(probeCountOf(adapter, classNamed(adapter)), 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classesAtMapTime.toString(),
                                execA.toString())));

        // The loader recompiled that class, which the selection should have treated as a loader
        // change and run every coverage test: a map that cannot apply is a red run.
        Path classesAtRunTime =
                loaderClassesDir(tmp, "loader-at-run-time", adapter, classChangedNamed(adapter));
        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        assertEquals(
                1,
                CoverageMapTool.run(
                        reconstructArgs(
                                map,
                                coreClasses,
                                tests,
                                outDir,
                                "test-loader",
                                classesAtRunTime.toString())));
    }

    @Test
    void reconstructRefusesATestTheMapDoesNotHold(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(SAMPLE, classBytes);
        Path coreClasses = classesDir(tmp, classBytes);
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        // A test that ran on no loader of the recorded run holds nothing to carry over.
        Files.writeString(tests, "coverage/tests/never-ran-Test.grug\n");
        assertEquals(
                1,
                CoverageMapTool.run(
                        reconstructArgs(
                                map,
                                coreClasses,
                                tests,
                                outDir,
                                "test-loader",
                                classes.toString())));
    }

    @Test
    void reconstructRefusesALoaderTheMapDoesNotHold(@TempDir Path tmp) throws Exception {
        // A loader this run measured but the map's run did not has no recorded coverage, so
        // carrying a test over there would leave its classes uncounted: the run fails instead.
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(SAMPLE, classBytes);
        Path coreClasses = classesDir(tmp, classBytes);
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        assertEquals(
                1,
                CoverageMapTool.run(
                        reconstructArgs(
                                map,
                                coreClasses,
                                tests,
                                tmp.resolve("carry-over"),
                                "another-loader",
                                classes.toString())));
    }

    @Test
    void twoLoadersWithDifferentClassesKeepTheirOwnIds(@TempDir Path tmp) throws Exception {
        // Two loaders compile one class name with different code, which is why the map is per
        // loader: each loader's carried exec must hold its own class's id, or that loader's
        // report would count nothing.
        byte[] coreBytes = sampleClass();
        long coreId = CoverageMapTool.classId(coreBytes);
        int coreProbes = probeCountOf(SAMPLE, coreBytes);
        Path coreClasses = classesDir(tmp, coreBytes);

        String adapter = "net/grug/covmaptest/Adapter";
        byte[] adapterA = classNamed(adapter);
        byte[] adapterB = classChangedNamed(adapter);
        long idA = CoverageMapTool.classId(adapterA);
        long idB = CoverageMapTool.classId(adapterB);
        assertTrue(idA != idB, "the two loaders' classes differ");
        Path classesA = loaderClassesDir(tmp, "loader-a/classes", adapter, adapterA);
        Path classesB = loaderClassesDir(tmp, "loader-b/classes", adapter, adapterB);

        Path execA = tmp.resolve("exec-a");
        Path execB = tmp.resolve("exec-b");
        writeExec(
                execA,
                "000-coverage.tests.a-Test.grug.exec",
                SAMPLE,
                coreId,
                probes(coreProbes, 0));
        writeExec(
                execA,
                "001-coverage.tests.a-Test.grug.exec",
                adapter,
                idA,
                probes(probeCountOf(adapter, adapterA), 0));
        writeExec(
                execB,
                "000-coverage.tests.a-Test.grug.exec",
                SAMPLE,
                coreId,
                probes(coreProbes, 1));
        writeExec(
                execB,
                "001-coverage.tests.a-Test.grug.exec",
                adapter,
                idB,
                probes(probeCountOf(adapter, adapterB), 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "loader-a",
                                classesA.toString(),
                                execA.toString(),
                                "loader-b",
                                classesB.toString(),
                                execB.toString())));

        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        Path out = tmp.resolve("carry-over");
        assertEquals(
                0,
                CoverageMapTool.run(
                        reconstructArgs(
                                map,
                                coreClasses,
                                tests,
                                out,
                                "loader-a",
                                classesA.toString(),
                                "loader-b",
                                classesB.toString())));

        ExecutionDataStore carriedA =
                readExec(out.resolve("loader-a").resolve("coverage.tests.a-Test.grug.exec"));
        ExecutionDataStore carriedB =
                readExec(out.resolve("loader-b").resolve("coverage.tests.a-Test.grug.exec"));
        assertEquals(idA, dataNamed(carriedA, adapter).getId());
        assertEquals(idB, dataNamed(carriedB, adapter).getId());
        // Core is one class in both runs, and each loader's exec holds it, so core's report
        // counts it from whichever loader ran the test.
        assertEquals(coreId, dataNamed(carriedA, SAMPLE).getId());
        assertEquals(coreId, dataNamed(carriedB, SAMPLE).getId());
    }

    @Test
    void buildRecordsAMapWithoutTestsWhenEveryTestWasCarriedOver(@TempDir Path tmp)
            throws Exception {
        // A selection that carries every coverage test over leaves no per-test exec data, and a
        // green run must not go red for a map its pull request never reads back.
        Path coreClasses = classesDir(tmp, sampleClass());
        Path classes = quietLoaderClasses(tmp, "loader/classes");
        Path execA = tmp.resolve("execA");
        writeExec(
                execA,
                "000-boot.exec",
                SAMPLE,
                CoverageMapTool.classId(sampleClass()),
                probes(probeCountOf(SAMPLE, sampleClass()), 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        buildArgs(
                                coreClasses,
                                map,
                                "test-loader",
                                classes.toString(),
                                execA.toString())));

        JsonObject tests =
                JsonParser.parseString(Files.readString(map))
                        .getAsJsonObject()
                        .getAsJsonObject("loaders")
                        .getAsJsonObject("test-loader")
                        .getAsJsonObject("tests");
        assertEquals(0, tests.size());
    }

    @Test
    void buildFailsOnAUsageErrorRatherThanReportingSuccess() {
        // A usage error must fail the step: exit 0 would let the coverage job carry on with no
        // map, which is exactly what the tool must never report as done (#786).
        assertEquals(2, CoverageMapTool.run(new String[] {"build", "--nope"}));
        assertEquals(2, CoverageMapTool.run(new String[] {"build"}));
        assertEquals(2, CoverageMapTool.run(new String[] {"build", "--classes", "."}));
        assertEquals(2, CoverageMapTool.run(new String[] {"build", "--loader", "x"}));
    }

    @Test
    void reconstructFailsOnAUsageErrorRatherThanReportingSuccess() {
        assertEquals(2, CoverageMapTool.run(new String[] {"reconstruct", "--nope"}));
        assertEquals(2, CoverageMapTool.run(new String[] {"reconstruct"}));
        assertEquals(2, CoverageMapTool.run(new String[] {"reconstruct", "--map", "map.json"}));
        // A carry-over with no loader has no report to count it, so it is a usage error too.
        assertEquals(
                2,
                CoverageMapTool.run(
                        new String[] {
                            "reconstruct",
                            "--map",
                            "map.json",
                            "--classes",
                            ".",
                            "--tests",
                            "tests.txt",
                            "--out-dir",
                            "out"
                        }));
    }
}
