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
 * each test covered, keyed by class and probe, and reconstruct applies a recorded map to a run's
 * classes, failing the run when a class it names changed under it.
 */
class CoverageMapToolTest {

    private static final String SAMPLE = "net/grug/covmaptest/Sample";

    /** A synthetic class, the shape of the marker classes the core build holds. */
    private static final String SYNTHETIC = "net/grug/covmaptest/Sample$1";

    /** A class file holding one public method with a branch, so it has more than one probe. */
    private static byte[] sampleClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SAMPLE, null, "java/lang/Object", null);
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
    private static byte[] sampleClassChanged() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SAMPLE, null, "java/lang/Object", null);
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
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SAMPLE, "never", "()V", false);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
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
    private static int probeCountOf(byte[] classBytes) throws Exception {
        int[] total = {0};
        var coverage = new org.jacoco.core.internal.analysis.ClassCoverageImpl(SAMPLE, 0L, false);
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
        Path dir = tmp.resolve("classes");
        Path file = dir.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, classBytes);
        return dir;
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

    private static boolean[] probes(int count, int... hit) {
        boolean[] out = new boolean[count];
        for (int p : hit) {
            out[p] = true;
        }
        return out;
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
    void buildRecordsWhatEachTestCoveredUnionedOverTheLoaders(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(classBytes);
        assertTrue(probeCount >= 2, "the sample class has more than one probe");
        Path classes = classesDir(tmp, classBytes);

        // Two loaders, in two exec directories: the same test hit probe 0 on one and probe 1 on
        // the other, and another test hit probe 0. The boot's exec, a dropped file and a class
        // the run's core does not hold are all there, and all of them are skipped.
        Path execA = tmp.resolve("execA");
        Path execB = tmp.resolve("execB");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));
        writeExec(
                execA,
                "001-coverage.tests.b-Test.grug.exec",
                SAMPLE,
                id,
                probes(probeCount, 0, probeCount - 1));
        writeExec(execA, "002-boot.exec", SAMPLE, id, probes(probeCount, 1));
        writeExec(execA, "003-jei.tests.a-Test.grug.dropped", SAMPLE, id, probes(probeCount, 2));
        writeExec(execB, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 1));
        writeExec(
                execB, "001-loader.tests.a-Test.grug.exec", "loader/Other", 0x1234L, probes(2, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        new String[] {
                            "build",
                            "--classes",
                            classes.toString(),
                            "--execs",
                            execA.toString(),
                            "--execs",
                            execB.toString(),
                            "--commit",
                            "deadbeef",
                            "--out",
                            map.toString()
                        }));

        JsonObject tree = JsonParser.parseString(Files.readString(map)).getAsJsonObject();
        assertEquals(1L, tree.get("version").getAsLong());
        assertEquals("deadbeef", tree.get("commit").getAsString());
        JsonElement classInfo = tree.getAsJsonObject("classes").get(SAMPLE);
        assertEquals(
                Long.toUnsignedString(id), classInfo.getAsJsonObject().get("crc64").getAsString());
        assertEquals(probeCount, classInfo.getAsJsonObject().get("probes").getAsInt());

        JsonElement a = tree.getAsJsonObject("tests").get("coverage/tests/a-Test.grug");
        JsonObject aClass = a.getAsJsonObject().get(SAMPLE).getAsJsonObject();
        assertEquals(List.of(0, 1), probeList(aClass.get("probes").getAsJsonArray()));
        assertTrue(
                aClass.get("methods").toString().contains("hit"),
                "the executed method is recorded: " + aClass);

        JsonElement b = tree.getAsJsonObject("tests").get("coverage/tests/b-Test.grug");
        assertEquals(
                List.of(0, probeCount - 1),
                probeList(
                        b.getAsJsonObject()
                                .get(SAMPLE)
                                .getAsJsonObject()
                                .get("probes")
                                .getAsJsonArray()));
        // The loader's own class is not a core class, so it is not in the map, and neither is
        // the test it is the exec data of.
        assertFalse(tree.getAsJsonObject("classes").has("loader/Other"));
        assertFalse(tree.getAsJsonObject("tests").has("loader/tests/a-Test.grug"));
    }

    private static List<Integer> probeList(JsonArray array) {
        List<Integer> out = new ArrayList<>();
        for (JsonElement p : array) {
            out.add(p.getAsInt());
        }
        return out;
    }

    @Test
    void buildReadsTheSessionHeaderTheAgentStampsOnEveryDump(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(classBytes);
        Path classes = classesDir(tmp, classBytes);
        Path execA = tmp.resolve("execA");
        writeAgentDump(
                execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        new String[] {
                            "build",
                            "--classes",
                            classes.toString(),
                            "--execs",
                            execA.toString(),
                            "--commit",
                            "cafebabe",
                            "--out",
                            map.toString()
                        }));

        JsonElement a =
                JsonParser.parseString(Files.readString(map))
                        .getAsJsonObject()
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
        int probeCount = probeCountOf(classBytes);
        // The analysis records nothing for a synthetic class, and the id of one is the error
        // that says so: the map records only what the analysis records.
        assertNull(CoverageMapTool.classIdIfAnalyzed(syntheticClass()));
        assertThrows(IOException.class, () -> CoverageMapTool.classId(syntheticClass()));
        Path classes = classesDir(tmp, classBytes);
        classesDir(tmp, SYNTHETIC, syntheticClass());
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        new String[] {
                            "build",
                            "--classes",
                            classes.toString(),
                            "--execs",
                            execA.toString(),
                            "--commit",
                            "cafebabe",
                            "--out",
                            map.toString()
                        }));

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
        int probeCount = probeCountOf(classBytes);
        Path classes = classesDir(tmp, classBytes);
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        new String[] {
                            "build",
                            "--classes",
                            classes.toString(),
                            "--execs",
                            execA.toString(),
                            "--commit",
                            "cafebabe",
                            "--out",
                            map.toString()
                        }));

        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        assertEquals(
                0,
                CoverageMapTool.run(
                        new String[] {
                            "reconstruct",
                            "--map",
                            map.toString(),
                            "--classes",
                            classes.toString(),
                            "--tests",
                            tests.toString(),
                            "--out-dir",
                            outDir.toString()
                        }));

        Path exec = outDir.resolve("coverage.tests.a-Test.grug.exec");
        assertTrue(Files.isRegularFile(exec), "the carried-over test's exec was written");
        ExecutionDataStore store = new ExecutionDataStore();
        try (InputStream in = Files.newInputStream(exec)) {
            ExecutionDataReader reader = new ExecutionDataReader(in);
            reader.setExecutionDataVisitor(store::put);
            while (reader.read()) {
                // the store took the entry
            }
        }
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
        int probeCount = probeCountOf(classBytes);
        // The map is built with the synthetic class among the run's classes, so it holds no
        // entry for it, and this run's classes hold it too.
        Path classes = classesDir(tmp, classBytes);
        classesDir(tmp, SYNTHETIC, syntheticClass());
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        new String[] {
                            "build",
                            "--classes",
                            classes.toString(),
                            "--execs",
                            execA.toString(),
                            "--commit",
                            "cafebabe",
                            "--out",
                            map.toString()
                        }));

        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        assertEquals(
                0,
                CoverageMapTool.run(
                        new String[] {
                            "reconstruct",
                            "--map",
                            map.toString(),
                            "--classes",
                            classes.toString(),
                            "--tests",
                            tests.toString(),
                            "--out-dir",
                            outDir.toString()
                        }));

        // The carried-over exec holds the one recorded class with its recorded probe, and
        // nothing of the synthetic class the map does not record.
        ExecutionDataStore store = new ExecutionDataStore();
        Path exec = outDir.resolve("coverage.tests.a-Test.grug.exec");
        try (InputStream in = Files.newInputStream(exec)) {
            ExecutionDataReader reader = new ExecutionDataReader(in);
            reader.setExecutionDataVisitor(store::put);
            while (reader.read()) {
                // the store took the entry
            }
        }
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
        int probeCount = probeCountOf(sampleClass());
        Path classesAtMapTime = classesDir(tmp.resolve("at-map-time"), sampleClass());
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
                        new String[] {
                            "build",
                            "--classes",
                            classesAtMapTime.toString(),
                            "--execs",
                            execA.toString(),
                            "--commit",
                            "cafebabe",
                            "--out",
                            map.toString()
                        }));

        // This run's class is a changed one: the same method with more in it, a different id.
        Path classesAtRunTime = classesDir(tmp.resolve("at-run-time"), sampleClassChanged());
        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        Files.writeString(tests, "coverage/tests/a-Test.grug\n");
        assertEquals(
                1,
                CoverageMapTool.run(
                        new String[] {
                            "reconstruct",
                            "--map",
                            map.toString(),
                            "--classes",
                            classesAtRunTime.toString(),
                            "--tests",
                            tests.toString(),
                            "--out-dir",
                            outDir.toString()
                        }));
    }

    @Test
    void reconstructRefusesATestTheMapDoesNotHold(@TempDir Path tmp) throws Exception {
        byte[] classBytes = sampleClass();
        long id = CoverageMapTool.classId(classBytes);
        int probeCount = probeCountOf(classBytes);
        Path classes = classesDir(tmp, classBytes);
        Path execA = tmp.resolve("execA");
        writeExec(execA, "000-coverage.tests.a-Test.grug.exec", SAMPLE, id, probes(probeCount, 0));

        Path map = tmp.resolve("map.json");
        assertEquals(
                0,
                CoverageMapTool.run(
                        new String[] {
                            "build",
                            "--classes",
                            classes.toString(),
                            "--execs",
                            execA.toString(),
                            "--commit",
                            "cafebabe",
                            "--out",
                            map.toString()
                        }));

        Path outDir = tmp.resolve("carry-over");
        Path tests = tmp.resolve("tests.txt");
        // A test that ran on no loader of the recorded run holds nothing to carry over.
        Files.writeString(tests, "coverage/tests/never-ran-Test.grug\n");
        assertEquals(
                1,
                CoverageMapTool.run(
                        new String[] {
                            "reconstruct",
                            "--map",
                            map.toString(),
                            "--classes",
                            classes.toString(),
                            "--tests",
                            tests.toString(),
                            "--out-dir",
                            outDir.toString()
                        }));
    }

    @Test
    void buildRefusesARunWithNoPerTestCoverage(@TempDir Path tmp) throws Exception {
        Path classes = classesDir(tmp, sampleClass());
        Path execA = tmp.resolve("execA");
        writeExec(
                execA,
                "000-boot.exec",
                SAMPLE,
                CoverageMapTool.classId(sampleClass()),
                probes(probeCountOf(sampleClass()), 0));
        assertEquals(
                1,
                CoverageMapTool.run(
                        new String[] {
                            "build",
                            "--classes",
                            classes.toString(),
                            "--execs",
                            execA.toString(),
                            "--commit",
                            "cafebabe",
                            "--out",
                            tmp.resolve("map.json").toString()
                        }));
    }
}
