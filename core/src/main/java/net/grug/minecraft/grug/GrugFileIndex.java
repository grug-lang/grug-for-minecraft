package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates and classifies the files grug-rs compiled, so the loaders' startup loops stay thin and
 * the validation branches are Java-testable.
 */
public final class GrugFileIndex {
    private GrugFileIndex() {}

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

    /**
     * Classifies every compiled file, rejecting one that failed to compile or that sits outside a
     * {@code code/} directory.
     */
    public static List<Entry> classify(FileInfo[] files) {
        List<Entry> result = new ArrayList<>();
        for (FileInfo file : files) {
            if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
                throw new IllegalStateException(
                        "Failed to compile " + file.path() + ":\n" + file.errorString());
            }

            String[] pathParts = file.path().replace('\\', '/').split("/");
            if (pathParts.length < 2 || !pathParts[1].equals("code")) {
                throw new IllegalStateException(
                        "Grug file misplaced! '"
                                + file.path()
                                + "' must be placed inside a 'code/' directory.");
            }

            String cleanName =
                    file.entityName().contains("-")
                            ? file.entityName().split("-")[0]
                            : file.entityName();
            result.add(new Entry(file, cleanName));
        }
        return result;
    }
}
