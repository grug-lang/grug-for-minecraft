package net.grug.minecraft.covmap;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Records, per coverage test, what the test covered, and applies a recorded map to a run's classes
 * so a later run can carry a test's coverage over instead of rerunning the test (#786).
 *
 * <p>{@code build} analyses the per-test exec files the agent cut during a run, against the run's
 * own core classes, in one JVM (one invocation over every file, not one per file), and writes a map
 * of what each test covered: the core classes the test reached, and per class the executed methods
 * (name, descriptor) and the probes it hit. A probe is JaCoCo's marker of a basic block, so the
 * keys are class, method and block rather than line, and a body edit that keeps the flow keeps the
 * keys. The map is data from the commit it was built on, which is why it is a CI artefact and never
 * a file of the repository.
 *
 * <p>{@code reconstruct} takes a map from an earlier commit and the classes of the commit being
 * built, and writes one exec file per named test, holding the probes the map says that test hit.
 * The gate then counts those exec files like the exec files of the tests that ran. Every class the
 * map names is resolved by name against the run's classes and its id checked: a class the map
 * cannot apply to is a class that changed under it, which means the change reached core and the
 * selection should have run those tests, so the tool fails the run rather than carry over coverage
 * that may not hold.
 *
 * <p>The exec format and the class analysis are the JaCoCo core library's own, so nothing here
 * reinterprets the data the gate itself will read.
 */
public final class CoverageMapTool {

    /** The mod whose tests the map records, matching GrugCoverage's counted mod. */
    static final String COUNTED_MOD = "coverage";

    /**
     * The per-test exec files, named NNN-<flattened test path>.exec, where flattening turns the
     * test path's slashes into dots, so coverage/tests/a-Test.grug is 001-coverage.tests.a-Test
     * .grug.exec.
     */
    private static final Pattern PER_TEST_EXEC = Pattern.compile("(\\d{3})-(.+)\\.exec");

    private CoverageMapTool() {}

    /** A failure the run must stop on, with the reason the log has to say. */
    static final class ToolError extends Exception {
        final int exit;

        ToolError(int exit, String message) {
            super(message);
            this.exit = exit;
        }

        int exit() {
            return exit;
        }
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Visible for tests: the command, as an exit code rather than a {@link System#exit} call. */
    static int run(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: CoverageMapTool " + "build|reconstruct ...");
            return 2;
        }
        try {
            if (args[0].equals("build")) {
                build(args);
            } else if (args[0].equals("reconstruct")) {
                reconstruct(args);
            } else {
                System.err.println("usage: CoverageMapTool " + "build|reconstruct ...");
                return 2;
            }
            return 0;
        } catch (ToolError e) {
            System.err.println("coverage-map-tool: " + e.getMessage());
            return e.exit();
        } catch (IOException e) {
            System.err.println("coverage-map-tool: " + e);
            return 1;
        }
    }

    /** The JaCoCo id of a class file, the CRC64 of its bytes, which pins the class. */
    static long classId(byte[] classBytes) throws IOException {
        CoverageBuilder builder = new CoverageBuilder();
        new Analyzer(new ExecutionDataStore(), builder)
                .analyzeClass(new ByteArrayInputStream(classBytes), null);
        return builder.getClasses().iterator().next().getId();
    }

    // The flattening is a bijection for the paths the runner hands over, which are exactly
    // mod/tests/name.grug, so the inverse is the first two dot groups as directories and the
    // rest as the file name.

    static String testPathOf(String flattened) {
        int first = flattened.indexOf('.');
        int second = flattened.indexOf('.', first + 1);
        if (first < 0 || second < 0) {
            throw new IllegalArgumentException("not a flattened test path: " + flattened);
        }
        return flattened.substring(0, first)
                + "/"
                + flattened.substring(first + 1, second)
                + "/"
                + flattened.substring(second + 1);
    }

    static String flattenedOf(String testPath) {
        return testPath.replace('/', '.');
    }

    /** The core classes under dir, by class name, with their files' bytes. */
    static Map<String, byte[]> readClasses(Path dir) throws ToolError {
        Map<String, byte[]> classes = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files =
                    walk.filter(
                                    p ->
                                            Files.isRegularFile(p)
                                                    && p.getFileName()
                                                            .toString()
                                                            .endsWith(".class"))
                            .toList();
        } catch (IOException e) {
            throw new ToolError(1, "could not read the class files under " + dir + ": " + e);
        }
        for (Path file : files) {
            String name = dir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            name = name.substring(0, name.length() - ".class".length());
            try {
                classes.put(name, Files.readAllBytes(file));
            } catch (IOException e) {
                throw new ToolError(1, "could not read the class file " + file + ": " + e);
            }
        }
        if (classes.isEmpty()) {
            throw new ToolError(1, "no class files under " + dir + " to analyse against");
        }
        return classes;
    }

    static void build(String[] args) throws IOException, ToolError {
        Path classesDir = null;
        List<Path> execDirs = new ArrayList<>();
        String commit = "";
        Path out = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--classes" -> classesDir = Path.of(args[++i]);
                case "--execs" -> execDirs.add(Path.of(args[++i]));
                case "--commit" -> commit = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    System.err.println("coverage-map-tool build: unknown argument " + args[i]);
                    return;
                }
            }
        }
        if (classesDir == null || execDirs.isEmpty() || out == null) {
            System.err.println(
                    "usage: CoverageMapTool build --classes <dir> --execs <dir>... "
                            + "--commit <sha> --out <file>");
            return;
        }

        Map<String, byte[]> classes = readClasses(classesDir);
        Map<String, Long> classIds = new TreeMap<>();
        for (Map.Entry<String, byte[]> classFile : classes.entrySet()) {
            classIds.put(classFile.getKey(), classId(classFile.getValue()));
        }

        // test path -> class name -> the probes the test hit in that class, unioned over every
        // loader's exec for the test, so the recorded coverage is what the test hit on any of
        // them and not on all of them.
        Map<String, Map<String, Set<Integer>>> hits = new TreeMap<>();
        Map<String, Integer> probeCounts = new TreeMap<>();
        for (Path execDir : execDirs) {
            List<Path> execs;
            try (Stream<Path> walk = Files.list(execDir)) {
                execs = walk.filter(Files::isRegularFile).toList();
            } catch (IOException e) {
                throw new ToolError(1, "could not read the exec files under " + execDir + ": " + e);
            }
            for (Path exec : execs) {
                Matcher matcher = PER_TEST_EXEC.matcher(exec.getFileName().toString());
                if (!matcher.matches()) {
                    continue; // the boot's exec, a dropped file, or no exec at all
                }
                String flattened = matcher.group(2);
                if (!flattened.startsWith(COUNTED_MOD + ".")) {
                    continue;
                }
                String test;
                try {
                    test = testPathOf(flattened);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                ExecutionDataStore store = new ExecutionDataStore();
                try (InputStream in = Files.newInputStream(exec)) {
                    ExecutionDataReader reader = new ExecutionDataReader(in);
                    reader.setExecutionDataVisitor(store::put);
                    while (reader.read()) {
                        // the store took the entry
                    }
                } catch (IOException e) {
                    throw new ToolError(1, "could not read the exec file " + exec + ": " + e);
                }
                for (ExecutionData data : store.getContents()) {
                    String class_ = data.getName();
                    Long id = classIds.get(class_);
                    if (id == null) {
                        // A class of the loader's own, which the map does not record.
                        continue;
                    }
                    if (!id.equals(data.getId())) {
                        throw new ToolError(
                                1,
                                "the exec file "
                                        + exec
                                        + " holds "
                                        + class_
                                        + " with an id that is not the id of the run's own"
                                        + " classes: the data is from another build");
                    }
                    probeCounts.put(class_, data.getProbes().length);
                    Set<Integer> hit =
                            hits.computeIfAbsent(test, k -> new TreeMap<>())
                                    .computeIfAbsent(class_, k -> new TreeSet<>());
                    boolean[] probes = data.getProbes();
                    for (int p = 0; p < probes.length; p++) {
                        if (probes[p]) {
                            hit.add(p);
                        }
                    }
                }
            }
        }
        if (hits.isEmpty()) {
            throw new ToolError(
                    1,
                    "no per-test coverage found under "
                            + execDirs
                            + ": the run held no exec data of the "
                            + COUNTED_MOD
                            + " mod's tests to record");
        }

        // The executed methods, per test and class: the JaCoCo core library assigns each probe's
        // hit to the instructions it guards, and a method counts as executed when any of its
        // instructions did.
        Map<String, Map<String, List<String>>> executedMethods = new TreeMap<>();
        for (Map.Entry<String, Map<String, Set<Integer>>> test : hits.entrySet()) {
            for (Map.Entry<String, Set<Integer>> classEntry : test.getValue().entrySet()) {
                byte[] classBytes = classes.get(classEntry.getKey());
                int probeCount = probeCounts.get(classEntry.getKey());
                boolean[] hit = new boolean[probeCount];
                for (int p : classEntry.getValue()) {
                    hit[p] = true;
                }
                ExecutionDataStore store = new ExecutionDataStore();
                store.put(
                        new ExecutionData(
                                classIds.get(classEntry.getKey()), classEntry.getKey(), hit));
                CoverageBuilder coverage = new CoverageBuilder();
                try {
                    new Analyzer(store, coverage)
                            .analyzeClass(new ByteArrayInputStream(classBytes), null);
                } catch (IOException e) {
                    throw new ToolError(
                            1,
                            "could not analyse the run's own class "
                                    + classEntry.getKey()
                                    + " for "
                                    + test.getKey()
                                    + ": "
                                    + e);
                }
                IClassCoverage classCoverage = coverage.getClasses().iterator().next();
                List<String> methods = new ArrayList<>();
                for (IMethodCoverage method : classCoverage.getMethods()) {
                    ICounter counter = method.getInstructionCounter();
                    if (counter.getCoveredCount() > 0) {
                        methods.add(method.getName() + ":" + method.getDesc());
                    }
                }
                executedMethods
                        .computeIfAbsent(test.getKey(), k -> new TreeMap<>())
                        .put(classEntry.getKey(), methods);
            }
        }

        out.toFile().getParentFile().mkdirs();
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            writeMap(w, commit, classIds, probeCounts, hits, executedMethods);
        } catch (IOException e) {
            throw new ToolError(1, "could not write the map to " + out + ": " + e);
        }
        int entries = 0;
        for (Map<String, Set<Integer>> perClass : hits.values()) {
            entries += perClass.size();
        }
        System.out.println(
                "coverage-map-tool: recorded "
                        + hits.size()
                        + " tests over "
                        + classIds.size()
                        + " core classes ("
                        + entries
                        + " test-class entries) in "
                        + out);
    }

    static void writeMap(
            BufferedWriter w,
            String commit,
            Map<String, Long> classIds,
            Map<String, Integer> probeCounts,
            Map<String, Map<String, Set<Integer>>> hits,
            Map<String, Map<String, List<String>>> executedMethods)
            throws IOException {
        w.write("{");
        w.write("\"version\":1,");
        w.write("\"commit\":\"" + json(commit) + "\",");
        w.write("\"classes\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> probeCount : probeCounts.entrySet()) {
            if (!first) {
                w.write(",");
            }
            first = false;
            w.write("\"" + json(probeCount.getKey()) + "\":{");
            w.write(
                    "\"crc64\":\""
                            + Long.toUnsignedString(classIds.get(probeCount.getKey()))
                            + "\",");
            w.write("\"probes\":" + probeCount.getValue());
            w.write("}");
        }
        w.write("},");
        w.write("\"tests\":{");
        first = true;
        for (Map.Entry<String, Map<String, Set<Integer>>> test : hits.entrySet()) {
            if (!first) {
                w.write(",");
            }
            first = false;
            w.write("\"" + json(test.getKey()) + "\":{");
            boolean firstClass = true;
            for (Map.Entry<String, Set<Integer>> classHits : test.getValue().entrySet()) {
                if (!firstClass) {
                    w.write(",");
                }
                firstClass = false;
                w.write("\"" + json(classHits.getKey()) + "\":{");
                List<String> methods =
                        executedMethods
                                .getOrDefault(test.getKey(), Map.of())
                                .getOrDefault(classHits.getKey(), List.of());
                w.write("\"methods\":[");
                for (int i = 0; i < methods.size(); i++) {
                    if (i > 0) {
                        w.write(",");
                    }
                    w.write("\"" + json(methods.get(i)) + "\"");
                }
                w.write("],");
                w.write("\"probes\":[");
                List<Integer> sorted = new ArrayList<>(classHits.getValue());
                for (int i = 0; i < sorted.size(); i++) {
                    if (i > 0) {
                        w.write(",");
                    }
                    w.write(Integer.toString(sorted.get(i)));
                }
                w.write("]");
                w.write("}");
            }
            w.write("}");
        }
        w.write("}");
        w.write("}");
    }

    static String json(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    static void reconstruct(String[] args) throws IOException, ToolError {
        Path mapPath = null;
        Path classesDir = null;
        Path testsPath = null;
        Path outDir = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--map" -> mapPath = Path.of(args[++i]);
                case "--classes" -> classesDir = Path.of(args[++i]);
                case "--tests" -> testsPath = Path.of(args[++i]);
                case "--out-dir" -> outDir = Path.of(args[++i]);
                default -> {
                    System.err.println(
                            "coverage-map-tool reconstruct: unknown argument " + args[i]);
                    return;
                }
            }
        }
        if (mapPath == null || classesDir == null || testsPath == null || outDir == null) {
            System.err.println(
                    "usage: CoverageMapTool reconstruct --map <file> --classes <dir> "
                            + "--tests <file> --out-dir <dir>");
            return;
        }

        Map<String, Object> map = readMap(Files.readString(mapPath));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> classes =
                (Map<String, Map<String, Object>>) map.get("classes");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Map<String, Object>>> recorded =
                (Map<String, Map<String, Map<String, Object>>>) map.get("tests");
        Map<String, byte[]> headClasses = readClasses(classesDir);
        Map<String, Long> headIds = new TreeMap<>();
        for (String class_ : headClasses.keySet()) {
            headIds.put(class_, classId(headClasses.get(class_)));
        }

        Files.createDirectories(outDir);
        List<String> tests = Files.readAllLines(testsPath);
        int written = 0;
        for (String test : tests) {
            if (test.isBlank()) {
                continue;
            }
            Map<String, Map<String, Object>> entry = recorded.get(test);
            if (entry == null || entry.isEmpty()) {
                throw new ToolError(
                        1,
                        "the map has no recorded coverage for "
                                + test
                                + ", so it cannot be carried over; the selection must have run"
                                + " it");
            }
            ExecutionDataStore store = new ExecutionDataStore();
            for (Map.Entry<String, Map<String, Object>> classEntry : entry.entrySet()) {
                String class_ = classEntry.getKey();
                byte[] classBytes = headClasses.get(class_);
                if (classBytes == null) {
                    throw new ToolError(
                            1,
                            "the recorded coverage of "
                                    + test
                                    + " holds the class "
                                    + class_
                                    + ", which is not among this run's core classes");
                }
                Map<String, Object> classInfo = classes.get(class_);
                if (classInfo == null) {
                    throw new ToolError(
                            1,
                            "the map has no class entry for "
                                    + class_
                                    + ", which "
                                    + test
                                    + " covered");
                }
                long storedId = Long.parseUnsignedLong((String) classInfo.get("crc64"));
                int probeCount = (int) ((Number) classInfo.get("probes")).longValue();
                long headId = headIds.get(class_);
                if (headId != storedId) {
                    throw new ToolError(
                            1,
                            "the class "
                                    + class_
                                    + " changed between the commit the map was recorded on and"
                                    + " this run, so the recorded coverage of the tests that"
                                    + " covered it cannot be applied; the selection must have run"
                                    + " those tests");
                }
                boolean[] probes = new boolean[probeCount];
                @SuppressWarnings("unchecked")
                List<Number> recordedProbes = (List<Number>) classEntry.getValue().get("probes");
                for (Number p : recordedProbes) {
                    int probe = p.intValue();
                    if (probe < 0 || probe >= probeCount) {
                        throw new ToolError(
                                1,
                                "the map's recorded probe "
                                        + probe
                                        + " of "
                                        + class_
                                        + " is out of range for the class's "
                                        + probeCount
                                        + " probes");
                    }
                    probes[probe] = true;
                }
                store.put(new ExecutionData(headId, class_, probes));
            }
            Path exec = outDir.resolve(flattenedOf(test) + ".exec");
            try (OutputStream out = Files.newOutputStream(exec)) {
                ExecutionDataWriter writer = new ExecutionDataWriter(out);
                for (ExecutionData data : store.getContents()) {
                    writer.visitClassExecution(data);
                }
                writer.flush();
            }
            written++;
            System.out.println(
                    "coverage-map-tool: carried over "
                            + test
                            + " from "
                            + store.getContents().size()
                            + " class(es)");
        }
        if (written == 0) {
            throw new ToolError(1, "no test named to carry over, so nothing was carried over");
        }
        System.out.println("coverage-map-tool: wrote " + written + " exec file(s) to " + outDir);
    }

    /**
     * A minimal JSON reader for the map the writer above emits: objects, arrays, strings, numbers
     * and the three literals, nothing else. The map holds no JSON the reader does not know, and a
     * map of another shape is a map this tool cannot trust, so it is refused.
     */
    static Map<String, Object> readMap(String text) throws IOException {
        Object value = new JsonReader(text).readValue();
        if (!(value instanceof Map)) {
            throw new IOException("the map is not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        if (!map.containsKey("version")
                || !map.containsKey("classes")
                || !map.containsKey("tests")) {
            throw new IOException("the map is missing version, classes or tests");
        }
        return map;
    }

    static final class JsonReader {
        private final String text;
        private int pos;

        JsonReader(String text) {
            this.text = text;
        }

        Object readValue() throws IOException {
            skipSpace();
            if (pos >= text.length()) {
                throw new IOException("the map ends before its value");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{' -> {
                    Map<String, Object> object = new LinkedHashMap<>();
                    pos++;
                    skipSpace();
                    if (peek() == '}') {
                        pos++;
                        return object;
                    }
                    while (true) {
                        String key = readString();
                        skipSpace();
                        if (!consume(':')) {
                            throw new IOException("the map's object holds no colon at " + pos);
                        }
                        object.put(key, readValue());
                        skipSpace();
                        if (consume(',')) {
                            continue;
                        }
                        if (consume('}')) {
                            return object;
                        }
                        throw new IOException("the map's object is unterminated at " + pos);
                    }
                }
                case '[' -> {
                    List<Object> array = new ArrayList<>();
                    pos++;
                    skipSpace();
                    if (peek() == ']') {
                        pos++;
                        return array;
                    }
                    while (true) {
                        array.add(readValue());
                        skipSpace();
                        if (consume(',')) {
                            continue;
                        }
                        if (consume(']')) {
                            return array;
                        }
                        throw new IOException("the map's array is unterminated at " + pos);
                    }
                }
                case '"' -> {
                    return readString();
                }
                case 't' -> {
                    if (text.startsWith("true", pos)) {
                        pos += 4;
                        return Boolean.TRUE;
                    }
                    throw new IOException("the map holds a bare true at " + pos);
                }
                case 'f' -> {
                    if (text.startsWith("false", pos)) {
                        pos += 5;
                        return Boolean.FALSE;
                    }
                    throw new IOException("the map holds a bare false at " + pos);
                }
                case 'n' -> {
                    if (text.startsWith("null", pos)) {
                        pos += 4;
                        return null;
                    }
                    throw new IOException("the map holds a bare null at " + pos);
                }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        int start = pos;
                        while (pos < text.length()
                                && (text.charAt(pos) == '-'
                                        || text.charAt(pos) == '.'
                                        || (text.charAt(pos) >= '0' && text.charAt(pos) <= '9'))) {
                            pos++;
                        }
                        return Long.parseLong(text.substring(start, pos));
                    }
                    throw new IOException("the map holds an unknown value at " + pos + ": " + c);
                }
            }
        }

        private char peek() throws IOException {
            if (pos >= text.length()) {
                throw new IOException("the map ends inside a value");
            }
            return text.charAt(pos);
        }

        private boolean consume(char expected) throws IOException {
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpace() {
            while (pos < text.length() && text.charAt(pos) <= ' ') {
                pos++;
            }
        }

        private String readString() throws IOException {
            if (peek() != '"') {
                throw new IOException("the map holds a string marker out of place at " + pos);
            }
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw new IOException("the map's string is unterminated");
                }
                char c = text.charAt(pos++);
                switch (c) {
                    case '"' -> {
                        return out.toString();
                    }
                    case '\\' -> {
                        char escape = text.charAt(pos++);
                        switch (escape) {
                            case '"' -> out.append('"');
                            case '\\' -> out.append('\\');
                            case '/' -> out.append('/');
                            case 'n' -> out.append('\n');
                            case 'r' -> out.append('\r');
                            case 't' -> out.append('\t');
                            case 'b' -> out.append('\b');
                            case 'f' -> out.append('\f');
                            case 'u' -> {
                                out.append(
                                        Character.toChars(
                                                Integer.parseInt(
                                                        text.substring(pos, pos + 4), 16)));
                                pos += 4;
                            }
                            default -> {
                                throw new IOException(
                                        "the map's string holds an unknown escape at " + pos);
                            }
                        }
                    }
                    default -> out.append(c);
                }
            }
        }
    }
}
