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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * one mod cannot address another. A path is a portable file name: it is rejected when it is
 * absolute, when it has a parent, empty or dot segment, when it holds a character Windows refuses
 * or a device name Windows keeps for itself, when a segment ends in a dot or a space, when it
 * differs only in case from an existing entry, or when any component is a symbolic link; a link is
 * refused rather than followed, because its target could be outside the directory. Writes are
 * atomic (a temporary file outside the mod's tree and a rename), so a crash leaves either the old
 * content or the new one, and the whole writable directory is capped at {@link #CAP} bytes.
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

    /**
     * The characters Windows refuses in a file name, besides a control character and the path
     * separators (a slash joins segments here, and a backslash is refused too).
     */
    private static final String ILLEGAL = ":*?\"<>|\\";

    /** The names Windows keeps for devices, with or without an extension and in any case. */
    private static final String[] RESERVED = {
        "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
        "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    };

    /** The directory beside the mods' directories that holds a write until its rename. */
    private static final String TEMP_DIRECTORY = ".tmp";

    /** The prefix and suffix of a write that has not been renamed yet. */
    private static final String TEMP_PREFIX = ".grug-write-";

    private static final String TEMP_SUFFIX = ".tmp";

    /**
     * Serializes the cap check, the temporary write and the move, so a write from the render thread
     * and one from the server thread cannot both pass the cap and land together.
     */
    private static final Object WRITE_LOCK = new Object();

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
     * The directory a write is made in before its rename, on the same filesystem but outside every
     * mod's {@code files} directory, so nothing a mod lists or counts ever sees a temporary file
     * and a crash leaves one where no call reads it.
     */
    static Path tempRoot(File gameDir) {
        return gameDir.toPath()
                .toAbsolutePath()
                .normalize()
                .resolve(WRITABLE_DIRECTORY)
                .resolve(TEMP_DIRECTORY);
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
     * path whose segments are all portable file names. A path is absolute, has a parent, dot or
     * empty segment, holds a character Windows refuses in a name (a backslash, a colon, a control
     * character or one of {@code * ? " < > |}), ends a segment in a dot or a space, or names a
     * device Windows keeps for itself. {@code "."} alone is allowed only for a listing, where it
     * names the mod's own directory.
     */
    static String pathProblem(String path, boolean allowRoot) {
        if (path == null || path.isEmpty()) {
            return "names no file in " + DIRECTORY + ".";
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
            if (holdsIllegal(segment)) {
                return "holds a character Windows refuses in a name, so it is not a portable file"
                        + " name.";
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                return "has a segment that ends in a dot or a space, which Windows strips, so it is"
                        + " not a portable file name.";
            }
            if (isReserved(segment)) {
                return "names \""
                        + segment
                        + "\", a device name Windows keeps for itself, so it is not a portable"
                        + " file name.";
            }
        }
        return null;
    }

    /** Whether a segment holds a control character or one of {@link #ILLEGAL}. */
    private static boolean holdsIllegal(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < 0x20 || ILLEGAL.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether a segment is a device name Windows reserves, with or without an extension. */
    private static boolean isReserved(String segment) {
        int dot = segment.indexOf('.');
        String stem = dot < 0 ? segment : segment.substring(0, dot);
        stem = stem.toLowerCase(Locale.ROOT);
        for (String reserved : RESERVED) {
            if (reserved.equals(stem)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The file {@code path} names under {@code root}, proven to stay inside it.
     *
     * @param allowRoot whether {@code "."} names {@code root} itself, which only a listing asks
     * @throws IllegalArgumentException when the path leaves the directory by its segments, or when
     *     any existing component, {@code root} included, is a symbolic link
     * @throws IOException when an existing component cannot be read
     */
    static Path resolve(Path root, String path, boolean allowRoot) throws IOException {
        String problem = pathProblem(path, allowRoot);
        if (problem != null) {
            throw new IllegalArgumentException("The path " + quote(path) + " " + problem);
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(path).normalize();
        Path component = base;
        refuseLink(component, path);
        for (Path part : base.relativize(target)) {
            component = component.resolve(part);
            refuseLink(component, path);
        }
        return target;
    }

    /**
     * Refuses a component that is a symbolic link rather than following it: a link is made outside
     * a script, and its target could be outside the mod's directory or feed the cap a byte count
     * nothing in the directory holds.
     */
    private static void refuseLink(Path component, String path) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs =
                    Files.readAttributes(
                            component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return; // nothing is there yet, and a write creates it
        }
        if (attrs.isSymbolicLink()) {
            throw new IllegalArgumentException(
                    "The path "
                            + quote(path)
                            + " goes through a symbolic link, and a link could leave the mod's "
                            + DIRECTORY
                            + " directory.");
        }
    }

    /**
     * The text of {@code path}, or null when neither the writable copy nor the shipped one exists.
     * A file that exists but whose bytes are not UTF-8 is reported instead.
     */
    static String read(File modsDir, File gameDir, String modName, String path) throws IOException {
        requireMod(modName);
        Path file = resolve(writableRoot(gameDir, modName), path, false);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return text(file, path);
        }
        Path shipped = resolve(shippedRoot(modsDir, modName), path, false);
        if (Files.isRegularFile(shipped, LinkOption.NOFOLLOW_LINKS)) {
            return text(shipped, path);
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(shipped, LinkOption.NOFOLLOW_LINKS)) {
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
        return Files.exists(
                        resolve(writableRoot(gameDir, modName), path, false),
                        LinkOption.NOFOLLOW_LINKS)
                || Files.exists(
                        resolve(shippedRoot(modsDir, modName), path, false),
                        LinkOption.NOFOLLOW_LINKS);
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
        boolean directories =
                Files.isDirectory(writable, LinkOption.NOFOLLOW_LINKS)
                        || Files.isDirectory(shipped, LinkOption.NOFOLLOW_LINKS);
        if (!directories
                && (Files.exists(writable, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(shipped, LinkOption.NOFOLLOW_LINKS))) {
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
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.forEach(entry -> names.add(entry.getFileName().toString()));
        }
    }

    /**
     * Writes {@code text} to {@code path} atomically, creating the directories it needs.
     *
     * <p>The bytes go to a temporary file in {@code <game directory>/grug_mod_files/.tmp/}, which
     * is on the same filesystem as every mod's directory but in none of them, and are renamed over
     * the target. A crash therefore leaves either the old content or the new one, and the only
     * trace a crash can leave is a file under {@code .tmp} that no read, listing or byte count ever
     * sees. The cap check, the temporary write and the move hold {@link #WRITE_LOCK}, so a write
     * from the render thread and one from the server thread cannot both pass the cap and land.
     *
     * @throws IllegalArgumentException when the path is refused, differs only in case from an
     *     existing entry, or the write would exceed {@link #CAP}
     * @throws IOException when the write itself fails, in which case the previous content is still
     *     there
     */
    static void write(File modsDir, File gameDir, String modName, String path, String text)
            throws IOException {
        requireMod(modName);
        Path root = writableRoot(gameDir, modName);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        synchronized (WRITE_LOCK) {
            Path target = resolve(root, path, false);
            requirePortableCase(shippedRoot(modsDir, modName), root, target, path);
            long previous = sizeOf(target);
            long after = totalBytes(root) - previous + bytes.length;
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

            Files.createDirectories(target.getParent());
            Path tempDir = tempRoot(gameDir);
            Files.createDirectories(tempDir);
            Path temp = Files.createTempFile(tempDir, TEMP_PREFIX, TEMP_SUFFIX);
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
    }

    /**
     * The bytes of the regular file {@code path} holds, or 0 when nothing regular is there. The
     * attributes are read without following a link, since {@link #resolve} has already refused one.
     */
    private static long sizeOf(Path path) throws IOException {
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isRegularFile() ? attrs.size() : 0;
        } catch (NoSuchFileException e) {
            return 0;
        }
    }

    /**
     * Refuses a write whose path, or whose directory, differs only in case from an existing entry
     * of either tree: on Windows that is one file and on this system two, so a refused write is
     * what makes a path mean the same on every loader.
     */
    private static void requirePortableCase(Path shipped, Path writable, Path target, String path)
            throws IOException {
        Path written = writable;
        Path shippedHere = shipped;
        for (Path part : writable.relativize(target)) {
            String name = part.toString();
            String clash = caseClash(written, name);
            if (clash == null) {
                clash = caseClash(shippedHere, name);
            }
            if (clash != null) {
                throw new IllegalArgumentException(
                        "The path "
                                + quote(path)
                                + " differs only in case from \""
                                + clash
                                + "\", which is one file on Windows and two on this system, so it"
                                + " is not a portable file name.");
            }
            written = written.resolve(name);
            shippedHere = shippedHere.resolve(name);
        }
    }

    /** The entry of {@code directory} that equals {@code name} ignoring case but not exactly. */
    private static String caseClash(Path directory, String name) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path entry : (Iterable<Path>) stream::iterator) {
                String existing = entry.getFileName().toString();
                if (!existing.equals(name) && existing.equalsIgnoreCase(name)) {
                    return existing;
                }
            }
        }
        return null;
    }

    /**
     * The bytes every regular file under {@code root} holds, which is what the cap counts. A link
     * is not counted and is never followed, so no link can feed the cap a target's size.
     */
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
