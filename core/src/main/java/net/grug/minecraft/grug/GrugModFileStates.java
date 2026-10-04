package net.grug.minecraft.grug;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Puts a mod file into a named test state, and puts it back again.
 *
 * <p>{@link #set} records a file's pristine bytes before it disturbs it, so that the {@code
 * "normal"} state restores it exactly. The record is a file beside the mods directory, not only a
 * value in memory, because a run can die between those two calls: a crash, a CI timeout, or any
 * failure that stops the test runner. Memory does not survive that, and a grug file left holding
 * the {@code invalid} marker is a file the next run cannot compile, which fails the whole run at
 * mod load while naming a stray character instead of the run that left it behind. {@link
 * #restoreInterruptedRuns} puts such a file back before anything is compiled, so a killed run costs
 * the next one nothing.
 *
 * <p>The record sits beside the mods directory rather than inside it for two reasons. Every loader
 * that shares a mods tree then shares the record, which is what lets a run of one loader be
 * recovered from by a run of another. And the engine watches the mods directory recursively and
 * reports every change in it, so a record written inside it would arrive as a changed resource and
 * reload the client's assets in the middle of a test. Inside it would also mean an extra directory
 * under the mods directory, which {@code GrugModLicenses} rejects for having no LICENSE.
 *
 * <p>Bytes rather than text, because a state can disturb an asset. A mod's {@code assets/} tree
 * holds PNGs, and a record that round-tripped them through a charset would store replacement
 * characters for every byte that is not valid in that charset, so the {@code "normal"} state would
 * put back a file that no longer decodes as the image it was.
 */
public final class GrugModFileStates {

    private static final String NORMAL = "normal";
    private static final String INVALID = "invalid";
    private static final String NO_TRAILING_NEWLINE = "no_trailing_newline";
    private static final String DELETED = "deleted";
    private static final String SWAPPED = "swapped";

    /** What the {@code "invalid"} state puts at the top of a file, which the compiler rejects. */
    private static final byte[] INVALID_MARKER = "!\n".getBytes(StandardCharsets.UTF_8);

    /** The byte {@link #NO_TRAILING_NEWLINE} takes off the end of a file. */
    private static final byte NEWLINE = (byte) '\n';

    /** Inserted into a file's name to find the file the {@code "swapped"} state copies in. */
    private static final String SWAP_INFIX = ".swap";

    /** The directory the records live in, beside the mods directory. */
    private static final String BACKUP_DIRECTORY = ".grug_test_backups";

    /** Suffix of a record of a mod file's pristine bytes. */
    private static final String BACKUP_SUFFIX = ".grugbak";

    /** Suffix of a record that is still being written, and so is not a record yet. */
    private static final String PENDING_SUFFIX = ".pending";

    @GrugGenerated("utility class: never instantiated")
    private GrugModFileStates() {}

    /**
     * Puts a mod file into a named state, recording its pristine bytes the first time so that the
     * {@code "normal"} state restores it exactly.
     *
     * <p>Idempotent on purpose: a hot-reload test sets a state, waits for {@code
     * Test.hot_reload_count} to rise, then sets it back to {@code "normal"}, so an aborted run
     * leaves the file disturbed rather than flipped, and a re-run sets the same state again.
     */
    public static void set(File modsDir, String relativePath, String state) throws IOException {
        Path path = modFile(modsDir, relativePath);
        Path backup = backupFor(modsDir, relativePath);

        if (NORMAL.equals(state)) {
            // No record means nothing ever disturbed the file, here or in an earlier run.
            if (!Files.isRegularFile(backup)) return;
            // Write the bytes back before dropping the record. A run killed in between leaves the
            // record behind, and the next startup would restore the very same bytes over the file.
            Files.write(path, Files.readAllBytes(backup));
            discardRecord(modsDir, backup);
            return;
        }

        if (!isDisturbance(state)) {
            throw new IllegalArgumentException(
                    "Unknown mod file state '"
                            + state
                            + "'. Expected \"normal\", \"invalid\", \"no_trailing_newline\","
                            + " \"deleted\" or \"swapped\".");
        }

        if (DELETED.equals(state)) {
            // The record is what lets "normal" and the next startup put the file back, so it is
            // written while the file is still there. A second delete keeps the first record, for
            // the same reason setting the same state twice does.
            if (Files.isRegularFile(path) && !Files.isRegularFile(backup)) {
                writeBackup(backup, Files.readAllBytes(path));
            }
            Files.deleteIfExists(path);
            return;
        }

        byte[] contents = Files.readAllBytes(path);
        // Only record while the file is still pristine. Recording the bytes of a file that is
        // already disturbed is what made a killed run permanent: the next run's "normal" then put
        // the marker straight back, and every run after that did the same.
        if (!Files.isRegularFile(backup)) {
            writeBackup(backup, contents);
        }

        Files.write(path, disturbed(path, contents, state));
    }

    /** Whether {@code state} disturbs a file, as opposed to the {@code "normal"} that undoes it. */
    private static boolean isDisturbance(String state) {
        return INVALID.equals(state)
                || NO_TRAILING_NEWLINE.equals(state)
                || DELETED.equals(state)
                || SWAPPED.equals(state);
    }

    /**
     * Puts back every mod file an earlier run left in a test state, reporting each file it had to
     * rewrite to {@code log}. A file an author already put back by hand is not reported, since
     * nothing changed.
     *
     * <p>Call this before the mods are compiled, or after it the next run reads the disturbed file.
     */
    public static void restoreInterruptedRuns(File modsDir, Consumer<String> log)
            throws IOException {
        File root = backupRoot(modsDir);
        if (!root.isDirectory()) return;

        for (Path backup : backupsIn(root.toPath())) {
            String relativePath = pathOfBackup(backup, root.toPath());
            if (putBack(modFile(modsDir, relativePath), backup)) {
                log.accept(
                        "Restored "
                                + relativePath
                                + ", which a run that ended abruptly had left in a test state.");
            }
        }

        // The whole directory goes, records included. A record still marked pending was being
        // written when its run died, and that run had not touched the file it was recording yet, so
        // there is nothing to put back and a half-written record must not be restored as truth.
        deleteTree(root.toPath());
    }

    /** Records {@code contents}, so that it survives even a run that never comes back. */
    private static void writeBackup(Path backup, byte[] contents) throws IOException {
        Files.createDirectories(backup.getParent());
        Path pending = backup.resolveSibling(backup.getFileName() + PENDING_SUFFIX);
        Files.write(pending, contents);

        // The rename is what makes the record safe to trust. Written in place, a run killed partway
        // through would leave a record holding a prefix of the file, and restoring that prefix is
        // the very corruption the record exists to prevent.
        Files.move(pending, backup, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Deletes a record and the directories that held it, so a healthy run leaves no trace beside
     * the mods rather than an empty tree of them.
     *
     * <p>Walks upwards instead of deleting the record's whole directory, because that directory is
     * shared with the records of every other file in the same mod and only this one is spent.
     */
    private static void discardRecord(File modsDir, Path backup) throws IOException {
        Files.delete(backup);
        // Up to and including the record directory itself, so the last record of a run leaves
        // nothing behind. The record directory is the end of the walk because nothing above it
        // belongs to grug.
        Path root = backupRootOf(modsDir.toPath());
        for (Path directory = backup.getParent(); ; directory = directory.getParent()) {
            if (!isEmpty(directory)) return;
            Files.delete(directory);
            if (directory.equals(root)) return;
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return !entries.findAny().isPresent();
        }
    }

    /** Writes the recorded bytes back over {@code path}, and reports whether it wrote anything. */
    private static boolean putBack(Path path, Path backup) throws IOException {
        byte[] pristine = Files.readAllBytes(backup);
        if (Files.isRegularFile(path) && Arrays.equals(pristine, Files.readAllBytes(path))) {
            // The file is already pristine, so an author who reverted it after a killed run gets it
            // left exactly as they made it rather than rewritten, timestamp and all.
            return false;
        }
        Files.createDirectories(path.getParent());
        Files.write(path, pristine);
        return true;
    }

    /** The mod file a record belongs to, in the {@code /} form the mods tree uses throughout. */
    private static String pathOfBackup(Path backup, Path root) {
        String relative = root.relativize(backup).toString();
        relative = relative.substring(0, relative.length() - BACKUP_SUFFIX.length());
        return relative.replace(File.separatorChar, '/');
    }

    /**
     * Every finished record under {@code root}, sorted, so that a run restoring several files names
     * them in the same order every time.
     */
    private static List<Path> backupsIn(Path root) throws IOException {
        List<Path> backups = new ArrayList<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().endsWith(BACKUP_SUFFIX)) {
                            backups.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        Collections.sort(backups);
        return backups;
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                            throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /**
     * Where the records live. Beside the mods directory, so that the loaders sharing a mods tree
     * share the records and a run killed on one loader is recovered by the next run of another.
     */
    private static File backupRoot(File modsDir) {
        return backupRootOf(modsDir.toPath()).toFile();
    }

    /** Visible for tests. */
    static Path backupRootOf(Path modsDir) {
        // Normalized rather than merely absolute, because the loaders spell the same mods
        // directory differently: the Forge ones reach it through "../../../mods", which names the
        // repository root only once the parent segments are collapsed.
        Path parent = modsDir.toAbsolutePath().normalize().getParent();
        return (parent == null ? modsDir : parent).resolve(BACKUP_DIRECTORY);
    }

    private static Path backupFor(File modsDir, String relativePath) {
        return backupRoot(modsDir).toPath().resolve(relativePath + BACKUP_SUFFIX);
    }

    /**
     * The mod file a mods-tree path names. Normalized, because a loader that spells the mods
     * directory as "run/../../../mods" resolves every file under it through those parent segments
     * and the intermediate directories need not exist.
     */
    private static Path modFile(File modsDir, String relativePath) {
        return modsDir.toPath().resolve(relativePath).normalize();
    }

    /**
     * The bytes that put {@code path} into {@code state}, which is not {@code "normal"}.
     *
     * @throws IOException when {@link #SWAPPED} asks for a file that is not there, since a swap
     *     with nothing to swap in would otherwise be a state that disturbs nothing and so never
     *     reports a change to wait for.
     */
    private static byte[] disturbed(Path path, byte[] contents, String state) throws IOException {
        if (INVALID.equals(state)) {
            byte[] marked = new byte[INVALID_MARKER.length + contents.length];
            System.arraycopy(INVALID_MARKER, 0, marked, 0, INVALID_MARKER.length);
            System.arraycopy(contents, 0, marked, INVALID_MARKER.length, contents.length);
            return marked;
        }
        if (SWAPPED.equals(state)) {
            return Files.readAllBytes(swapSibling(path));
        }
        // NO_TRAILING_NEWLINE. A file that has no trailing newline to begin with is already in
        // this state, so there is nothing to change.
        if (contents.length > 0 && contents[contents.length - 1] == NEWLINE) {
            return Arrays.copyOf(contents, contents.length - 1);
        }
        return contents;
    }

    /**
     * The file whose bytes the {@code "swapped"} state copies over {@code path}: its name with
     * {@code .swap} inserted before the extension, so {@code stone.png} is swapped by {@code
     * stone.swap.png} and a name with no extension at all by {@code LICENSE.swap}.
     *
     * <p>A name the state is derived from rather than passed in, so a test that swaps one file
     * cannot name a different one: {@code Test.set_mod_file_state} is a test-only host function and
     * the whole reason it can write into the mods tree is that a test needs to disturb a file the
     * game reloads from. Handing it a second path would let it overwrite anything on disk instead
     * of only swapping a file for the sibling that already sits beside it.
     */
    private static Path swapSibling(Path path) {
        String name = path.getFileName().toString();
        // A leading dot is a hidden file's, not an extension's, so ".gitignore" becomes
        // ".gitignore.swap" rather than ".swapgitignore".
        int dot = name.lastIndexOf('.');
        String swapped =
                dot <= 0
                        ? name + SWAP_INFIX
                        : name.substring(0, dot) + SWAP_INFIX + name.substring(dot);
        return path.resolveSibling(swapped);
    }
}
