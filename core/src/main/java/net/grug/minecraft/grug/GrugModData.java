package net.grug.minecraft.grug;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The one directory a mod may write, and the reads that see what it shipped there.
 *
 * <p>A mod's files live in two trees. The mod ships defaults in {@code <mods>/<mod>/files/}, which
 * the host never writes: in a development run that tree is the repository's own {@code mods/}, so a
 * write into it would dirty the checkout, and every loader's engine watches the mods directory and
 * reports every change under it. The writable copy is {@code <game
 * directory>/grug_mod_files/<mod>/files/}, which is per game directory and so per loader, and which
 * a player deletes to get the shipped defaults back. A read asks the writable copy first and falls
 * back to the shipped file, so a mod does not have to copy its defaults across, and a listing shows
 * the names of both.
 *
 * <p>Every call resolves against the caller's own mod directory. The mod name comes from the
 * resource literal the compiler rewrites to {@code <mod>/files}, so no entry takes a mod name and
 * one mod cannot address another. A path is rejected when it is absolute, when it has a parent,
 * empty or dot segment, when it names a drive or holds a character a filesystem refuses, when it
 * resolves outside the directory, or when an existing component is a symbolic link whose target
 * leaves the directory. Writes are atomic (a temporary file and a rename), so a crash leaves either
 * the old content or the new one, and the whole writable directory is capped at {@link #CAP} bytes.
 */
public final class GrugModData {

    /** The directory beside {@code code/}, {@code tests/} and {@code assets/} that holds files. */
    public static final String DIRECTORY = "files";

    /**
     * The directory under the game directory that holds every mod's writable copy, beside the
     * game's own directories rather than inside the watched mods directory.
     */
    public static final String WRITABLE_DIRECTORY = "grug_mod_files";

    /** The cap of one mod's writable directory, in bytes. */
    public static final long CAP = 16L * 1024 * 1024;

    /** The prefix and suffix of a write that has not been renamed yet. */
    private static final String TEMP_PREFIX = ".grug-write-";

    private static final String TEMP_SUFFIX = ".tmp";

    private GrugModData() {}

    /** The mod a resource names, from the {@code <mod>/files} the compiler rewrote it into. */
    static String modOf(String resource) {
        int slash = resource == null ? -1 : resource.lastIndexOf('/');
        if (slash <= 0 || slash == resource.length() - 1) {
            throw new IllegalArgumentException(
                    "the resource "
                            + resource
                            + " does not name <mod>/"
                            + DIRECTORY
                            + ", so it names no mod: pass r\""
                            + DIRECTORY
                            + "\"");
        }
        String mod = resource.substring(0, slash);
        String leaf = resource.substring(slash + 1);
        if (!DIRECTORY.equals(leaf)) {
            throw new IllegalArgumentException(
                    "the resource "
                            + resource
                            + " must name the mod's "
                            + DIRECTORY
                            + " directory, such as r\""
                            + DIRECTORY
                            + "\", not "
                            + leaf);
        }
        if (mod.indexOf('/') >= 0
                || mod.indexOf('\\') >= 0
                || mod.indexOf('\0') >= 0
                || mod.indexOf(':') >= 0
                || mod.equals(".")
                || mod.equals("..")) {
            throw new IllegalArgumentException(
                    "the resource " + resource + " names no mod directory");
        }
        return mod;
    }

    /** The directory a write goes to, and the one a read asks before the shipped copy. */
    static Path writableRoot(File gameDir, String modName) {
        return gameDir.toPath()
                .toAbsolutePath()
                .normalize()
                .resolve(WRITABLE_DIRECTORY)
                .resolve(modName)
                .resolve(DIRECTORY);
    }

    /** The directory a mod ships its defaults in, which this class only ever reads. */
    static Path shippedRoot(File modsDir, String modName) {
        return modsDir.toPath().toAbsolutePath().normalize().resolve(modName).resolve(DIRECTORY);
    }

    /**
     * Refuses a handle whose {@code File.open} failed, so it can never reach a shared directory.
     */
    private static void requireMod(String modName) {
        if (modName == null) {
            throw new IllegalArgumentException(
                    "the handle names no mod, because File.open reported why");
        }
    }

    /**
     * Why {@code path} is not a name this class accepts, or null when it is one: a plain relative
     * path whose segments are all names. A path that names no drive, holds no backslash, NUL or
     * colon, has no parent, dot or empty segment, and is not absolute. {@code "."} alone is allowed
     * only for a listing, where it names the mod's own directory.
     */
    static String pathProblem(String path, boolean allowRoot) {
        if (path == null || path.isEmpty()) {
            return "names no file in " + DIRECTORY + ".";
        }
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return "is not a plain relative path: use '/' between its segments.";
        }
        if (path.indexOf(':') >= 0) {
            return "holds a colon, which names a drive on Windows and is refused everywhere so a"
                    + " path means the same on every loader.";
        }
        if (path.startsWith("/")) {
            return "is absolute, and a path is always relative to the mod's own " + DIRECTORY + ".";
        }
        if (allowRoot && path.equals(".")) {
            return null;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return "has an empty or relative segment, and no path may climb out of "
                        + DIRECTORY
                        + ".";
            }
        }
        return null;
    }

    /**
     * The file {@code path} names under {@code root}, proven to stay inside it.
     *
     * @param allowRoot whether {@code "."} names {@code root} itself, which only a listing asks
     * @throws IllegalArgumentException when the path leaves the directory by its segments, or
     *     through a symbolic link on any existing component
     * @throws IOException when an existing component cannot be read, such as a dangling link
     */
    static Path resolve(Path root, String path, boolean allowRoot) throws IOException {
        String problem = pathProblem(path, allowRoot);
        if (problem != null) {
            throw new IllegalArgumentException("The path " + quote(path) + " " + problem);
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(path).normalize();
        if (!existingReal(target).startsWith(existingReal(base))) {
            throw new IllegalArgumentException(
                    "The path "
                            + quote(path)
                            + " leaves the mod's "
                            + DIRECTORY
                            + " directory through a symbolic link.");
        }
        return target;
    }

    /** The real path of the nearest ancestor of {@code path} that exists, itself included. */
    private static Path existingReal(Path path) throws IOException {
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        return existing.toRealPath();
    }

    /**
     * The text of {@code path}, or null when neither the writable copy nor the shipped one exists.
     * A file that exists but whose bytes are not UTF-8 is reported instead.
     */
    static String read(File modsDir, File gameDir, String modName, String path) throws IOException {
        requireMod(modName);
        Path file = resolve(writableRoot(gameDir, modName), path, false);
        if (Files.isRegularFile(file)) {
            return text(file, path);
        }
        Path shipped = resolve(shippedRoot(modsDir, modName), path, false);
        if (Files.isRegularFile(shipped)) {
            return text(shipped, path);
        }
        if (Files.exists(file) || Files.exists(shipped)) {
            throw new IllegalArgumentException(
                    "The path " + quote(path) + " names a directory, not a file.");
        }
        return null;
    }

    private static String text(Path file, String path) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "The file " + quote(path) + " is not UTF-8 text: " + e.getMessage());
        }
    }

    /**
     * Whether a file or a directory of that name exists, in the writable copy or the shipped one.
     */
    static boolean exists(File modsDir, File gameDir, String modName, String path)
            throws IOException {
        requireMod(modName);
        return Files.exists(resolve(writableRoot(gameDir, modName), path, false))
                || Files.exists(resolve(shippedRoot(modsDir, modName), path, false));
    }

    /** How many entries the directory of that name holds in both trees, or 0 for a missing one. */
    static int entryCount(File modsDir, File gameDir, String modName, String path)
            throws IOException {
        return entryNames(modsDir, gameDir, modName, path).size();
    }

    /** The name of the {@code index}th entry of the directory, sorted by name. */
    static String entryName(File modsDir, File gameDir, String modName, String path, double index)
            throws IOException {
        int i = indexProblem(index, path);
        List<String> names = entryNames(modsDir, gameDir, modName, path);
        if (i >= names.size()) {
            throw new IllegalArgumentException(
                    "The directory "
                            + quote(path)
                            + " has no entry "
                            + i
                            + ": it holds "
                            + names.size()
                            + ".");
        }
        return names.get(i);
    }

    private static int indexProblem(double index, String path) {
        if (!Double.isFinite(index)
                || index != Math.floor(index)
                || index < 0
                || index > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "The index "
                            + index
                            + " is not a whole number from 0 up, so it names no entry of "
                            + quote(path)
                            + ".");
        }
        return (int) index;
    }

    /**
     * The names under the directory {@code path}, from both trees, sorted and with a name in both
     * listed once. An entry that exists but is not a directory is reported.
     */
    private static List<String> entryNames(File modsDir, File gameDir, String modName, String path)
            throws IOException {
        requireMod(modName);
        Path writable = resolve(writableRoot(gameDir, modName), path, true);
        Path shipped = resolve(shippedRoot(modsDir, modName), path, true);
        boolean directories = Files.isDirectory(writable) || Files.isDirectory(shipped);
        if (!directories && (Files.exists(writable) || Files.exists(shipped))) {
            throw new IllegalArgumentException(
                    "The path " + quote(path) + " names a file, not a directory.");
        }
        Set<String> names = new TreeSet<>();
        addNames(writable, names);
        addNames(shipped, names);
        return new ArrayList<>(names);
    }

    /** Adds the names of the entries of {@code directory} when it is one. */
    private static void addNames(Path directory, Set<String> names) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.forEach(entry -> names.add(entry.getFileName().toString()));
        }
    }

    /**
     * Writes {@code text} to {@code path} atomically, creating the directories it needs.
     *
     * <p>The bytes go to a temporary file in the same directory and are renamed over the target, so
     * a crash leaves either the old content or the new one and never a half-written file. The
     * temporary file is not counted against the cap: it is transient, and the cap is about what the
     * mod keeps. A write the cap refuses never touches the previous content.
     *
     * @throws IllegalArgumentException when the path is refused or the write would exceed {@link
     *     #CAP}
     * @throws IOException when the write itself fails, in which case the previous content is still
     *     there
     */
    static void write(File gameDir, String modName, String path, String text) throws IOException {
        requireMod(modName);
        Path root = writableRoot(gameDir, modName);
        Path target = resolve(root, path, false);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long previous = Files.isRegularFile(target) ? Files.size(target) : 0;
        long total = totalBytes(root);
        long after = total - previous + bytes.length;
        if (after > CAP) {
            throw new IllegalArgumentException(
                    "Writing "
                            + quote(path)
                            + " would make the mod's "
                            + DIRECTORY
                            + " directory "
                            + after
                            + " bytes, over the "
                            + CAP
                            + "-byte cap.");
        }

        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX);
        boolean moved = false;
        try {
            Files.write(temp, bytes);
            Files.move(
                    temp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** The bytes every regular file under {@code root} holds, which is what the cap counts. */
    private static long totalBytes(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        final long[] total = {0};
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) {
                            total[0] += attrs.size();
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return total[0];
    }

    /** A path or a name as a message shows it, quoted so an empty one is visible. */
    private static String quote(String text) {
        return text == null ? "(no path)" : "\"" + text + "\"";
    }
}
