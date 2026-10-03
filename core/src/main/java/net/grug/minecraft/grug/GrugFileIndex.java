package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates and classifies the files grug-rs compiled, so the loaders' startup loops stay thin and
 * the validation branches are Java-testable.
 */
public final class GrugFileIndex {
    private GrugFileIndex() {}

    /**
     * The suffix that marks a grug file as a test, which is how {@code GrugTestRunner} finds them.
     */
    public static final String TEST_SUFFIX = "-Test.grug";

    /** A compiled file plus the clean entity name the loaders key their registries by. */
    public static final class Entry {
        private final FileInfo file;
        private final String cleanName;

        Entry(FileInfo file, String cleanName) {
            this.file = file;
            this.cleanName = cleanName;
        }

        public FileInfo file() {
            return file;
        }

        public String cleanName() {
            return cleanName;
        }

        public String entityType() {
            return file.entityType();
        }
    }

    /** The entity name with any {@code -suffix} stripped, as the loaders key their registries. */
    public static String cleanEntityName(String entityName) {
        return entityName.contains("-") ? entityName.split("-")[0] : entityName;
    }

    /**
     * Whether a mod-root subdirectory may hold grug scripts: {@code code} for the mod itself and
     * {@code tests} for its tests. Shared with the hot-reload check in {@link Grug#update}, which
     * has to accept the same set or an edited test would keep running its previous version.
     */
    public static boolean holdsScripts(String directoryName) {
        return directoryName.equals("code") || directoryName.equals("tests");
    }

    /**
     * The test files that are not under their mod's {@code tests/} directory.
     *
     * <p>A misplaced test still compiles and runs, so unlike a file outside {@code code/} it is an
     * err rather than a refusal to load. It is an err because it silently reorders the suite:
     * {@code GrugTestRunner} sorts tests by path, so a test left in {@code code/} runs before its
     * name says it should, and the tests share one world and leave fixtures behind for each other.
     */
    public static List<String> misplacedTests(FileInfo[] files) {
        List<String> misplaced = new ArrayList<>();
        for (FileInfo file : files) {
            if (!file.path().endsWith(TEST_SUFFIX)) continue;
            String[] pathParts = file.path().replace('\\', '/').split("/");
            // The second segment is the mod's own directory, so a test is in place once the third
            // segment is tests/. Anything deeper is a mod nesting its tests, which is allowed.
            if (pathParts.length < 3 || !pathParts[1].equals("tests")) {
                misplaced.add(file.path());
            }
        }
        return misplaced;
    }

    /**
     * Classifies every compiled file, rejecting one that failed to compile or that sits outside a
     * {@code code/} or {@code tests/} directory, and reporting any test file that is not under its
     * mod's {@code tests/}.
     */
    public static List<Entry> classify(FileInfo[] files) {
        List<Entry> result = new ArrayList<>();
        for (FileInfo file : files) {
            if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
                throw new IllegalStateException(
                        "Failed to compile " + file.path() + ":\n" + file.errorString());
            }

            String[] pathParts = file.path().replace('\\', '/').split("/");
            if (pathParts.length < 2 || !holdsScripts(pathParts[1])) {
                throw new IllegalStateException(
                        "Grug file misplaced! '"
                                + file.path()
                                + "' must be placed inside a 'code/' or 'tests/' directory.");
            }

            String cleanName = cleanEntityName(file.entityName());
            result.add(new Entry(file, cleanName));
        }
        reportMisplacedTests(misplacedTests(files));
        return result;
    }

    /**
     * Reports each misplaced test the way grug reports any other mod-tree err: to the player in
     * chat as a red message, and to the run as a {@code [GRUG CI] FAIL} line, which is what {@code
     * run-loader.sh} fails on. The game keeps running, because a misplaced test is a layout err and
     * not a broken mod.
     */
    private static void reportMisplacedTests(List<String> misplaced) {
        for (String path : misplaced) {
            GrugModTreeDefect.report(
                    "Test file misplaced: '"
                            + path
                            + "' must be placed inside its mod's 'tests/' directory, or the suite"
                            + " runs out of order.");
        }
    }
}
