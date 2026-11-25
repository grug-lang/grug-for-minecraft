package net.grug.minecraft.grug;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 * differs only in case from an existing entry, or when any component below the game directory or
 * the mods directory is a symbolic link or a Windows junction (a leading {@code "./"} is accepted,
 * so a listing's own name can be appended to {@code "."}); a link is refused rather than followed,
 * because its target could be outside the directory.
 *
 * <p>The case rule holds for every call, not only a write: a path that differs only in case from an
 * entry the call would answer from is refused rather than answered, because Windows and macOS keep
 * one file where this system keeps two, so answering it would make one path mean different files on
 * different loaders. A listing names only entries a read accepts, so a name it returns can always
 * be appended to its directory and read. The written copy decides what a name is: a written file
 * wins over a shipped file of that name and a written directory over a shipped file, so a read and
 * a listing agree on a name written as a directory and shipped as a file.
 *
 * <p>Writes are atomic (a temporary file outside the mod's tree and a rename), and the new bytes
 * are flushed to the disk before the rename and the directory after it where the platform can flush
 * one, so even a power loss leaves either the old content or the new one. Every entry is charged at
 * least one filesystem block of {@link #BLOCK} bytes against {@link #CAP}: a file costs its size or
 * one block when smaller, a directory one block, a directory the write itself creates included, and
 * a link nothing, so at most 4096 entries fit whatever their sizes and no collection of tiny files
 * can fill the disk while the count says otherwise.
 */
public final class GrugModData {

    /** The directory beside {@code code/}, {@code tests/} and {@code assets/} that holds files. */
    public static final String DIRECTORY = "files";

    /**
     * The directory under the game directory that holds every mod's writable copy, beside the
     * game's own directories rather than inside the watched mods directory.
     */
    public static final String WRITABLE_DIRECTORY = "grug_mod_files";

    /** The cap of one mod's writable directory, in charged bytes. */
    public static final long CAP = 16L * 1024 * 1024;

    /**
     * The charge of one entry that holds less than a filesystem block, and of every directory: the
     * filesystem spends at least one block on each entry's data or on each directory, whatever the
     * file's own size says.
     */
    static final long BLOCK = 4096;

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
     * device Windows keeps for itself. A leading {@code "./"} is accepted, because appending a
     * listing's own name to {@code "."} produces one. {@code "."} alone is allowed only for a
     * listing, where it names the mod's own directory.
     */
    static String pathProblem(String path, boolean allowRoot) {
        if (path == null || path.isEmpty()) {
            return "names no file in " + DIRECTORY + ".";
        }
        if (path.startsWith("/")) {
            return "is absolute, and a path is always relative to the mod's own " + DIRECTORY + ".";
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty()) {
            return "names no file in " + DIRECTORY + ".";
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
     * The file {@code path} names under {@code root}, proven to stay inside it and to pass no link.
     *
     * @param anchor the tree the walk starts at, the game directory for the writable copy and the
     *     mods directory for the shipped one, so a link above the mod's own directory is caught too
     * @param root the mod's {@code files} directory, which must sit under {@code anchor}
     * @param allowRoot whether {@code "."} names {@code root} itself, which only a listing asks
     * @throws IllegalArgumentException when the path leaves the directory by its segments, when
     *     {@code root} is not under {@code anchor}, or when any existing component below {@code
     *     anchor} is a symbolic link or a junction
     * @throws IOException when an existing component cannot be read
     */
    static Path resolve(Path anchor, Path root, String path, boolean allowRoot) throws IOException {
        String problem = pathProblem(path, allowRoot);
        if (problem != null) {
            throw new IllegalArgumentException("The path " + quote(path) + " " + problem);
        }
        Path start = anchor.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        if (!base.startsWith(start)) {
            throw new IllegalArgumentException(
                    "The directory " + base + " is not inside " + start + ".");
        }
        Path target = base.resolve(path).normalize();
        Path component = start;
        for (Path part : start.relativize(base)) {
            component = component.resolve(part);
            refuseLink(component, path);
        }
        for (Path part : base.relativize(target)) {
            component = component.resolve(part);
            refuseLink(component, path);
        }
        return target;
    }

    /**
     * Refuses a component that is a symbolic link or a junction rather than following it: either is
     * made outside a script, and its target could be outside the mod's directory or feed the cap a
     * byte count nothing in the directory holds. Windows reports a directory junction as neither a
     * symbolic link nor a regular file or directory, so {@link BasicFileAttributes#isOther()} is
     * the only arm that catches it.
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
        if (attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IllegalArgumentException(
                    "The path "
                            + quote(path)
                            + " goes through a symbolic link or a junction, and either could"
                            + " leave the mod's "
                            + DIRECTORY
                            + " directory.");
        }
    }

    /**
     * The text of {@code path}, or null when neither the writable copy nor the shipped one exists.
     * A file that exists but whose bytes are not UTF-8 is reported instead, and so is a written
     * directory, which wins over a shipped file of that name.
     */
    static String read(File modsDir, File gameDir, String modName, String path) throws IOException {
        requireMod(modName);
        Path writableRoot = writableRoot(gameDir, modName);
        Path shippedRoot = shippedRoot(modsDir, modName);
        Path file = resolve(gameDir.toPath(), writableRoot, path, false);
        Path shipped = resolve(modsDir.toPath(), shippedRoot, path, false);
        requirePortableCase(writableRoot, shippedRoot, file, path);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return text(file, path);
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "The path " + quote(path) + " names a directory, not a file.");
        }
        if (Files.isRegularFile(shipped, LinkOption.NOFOLLOW_LINKS)) {
            return text(shipped, path);
        }
        if (Files.exists(shipped, LinkOption.NOFOLLOW_LINKS)) {
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
     * Whether a file or a directory of that name exists, in the writable copy or the shipped one. A
     * path that differs only in case from an existing entry is reported rather than answered.
     */
    static boolean exists(File modsDir, File gameDir, String modName, String path)
            throws IOException {
        requireMod(modName);
        Path writableRoot = writableRoot(gameDir, modName);
        Path shippedRoot = shippedRoot(modsDir, modName);
        Path writable = resolve(gameDir.toPath(), writableRoot, path, false);
        Path shipped = resolve(modsDir.toPath(), shippedRoot, path, false);
        requirePortableCase(writableRoot, shippedRoot, writable, path);
        return Files.exists(writable, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(shipped, LinkOption.NOFOLLOW_LINKS);
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
     * The names under the directory {@code path}, from both trees, sorted, with a name in both
     * listed once and only names a read accepts. An entry that exists but is not a directory is
     * reported, and the written copy decides: a written file makes the path a file whatever the
     * shipped tree holds, and a written directory hides a shipped file of that name.
     */
    private static List<String> entryNames(File modsDir, File gameDir, String modName, String path)
            throws IOException {
        requireMod(modName);
        Path writableRoot = writableRoot(gameDir, modName);
        Path shippedRoot = shippedRoot(modsDir, modName);
        Path writable = resolve(gameDir.toPath(), writableRoot, path, true);
        Path shipped = resolve(modsDir.toPath(), shippedRoot, path, true);
        requirePortableCase(writableRoot, shippedRoot, writable, path);
        boolean writableThere = Files.exists(writable, LinkOption.NOFOLLOW_LINKS);
        Path shown = writableThere ? writable : shipped;
        if (!Files.isDirectory(shown, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(shown, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "The path " + quote(path) + " names a file, not a directory.");
            }
            return new ArrayList<>();
        }
        Set<String> names = new TreeSet<>();
        Set<String> folded = new TreeSet<>();
        addNames(writable, names, folded);
        addNames(shipped, names, folded);
        return new ArrayList<>(names);
    }

    /**
     * Adds the names of the entries of {@code directory} when it is one. A link is left out because
     * {@link #resolve} refuses one, and so is a name {@link #pathProblem} refuses and a name that
     * differs only in case from one already added, since a read of either would be refused, and a
     * listing must never name an entry a read cannot open. {@code folded} carries the names added
     * so far in lower case, so the written copy, added first, wins over a shipped name it
     * case-folds onto.
     */
    private static void addNames(Path directory, Set<String> names, Set<String> folded)
            throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<String> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.forEach(
                    entry -> {
                        String name = entry.getFileName().toString();
                        boolean plain =
                                Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                                        || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                        if (plain && pathProblem(name, false) == null) {
                            children.add(name);
                        }
                    });
        }
        // Sorted, because Files.list reports a directory's entries in no order and the name that
        // survives a case fold must be the same on every run.
        children.sort(null);
        for (String name : children) {
            if (folded.add(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }
    }

    /**
     * Writes {@code text} to {@code path} atomically, creating the directories it needs.
     *
     * <p>The bytes go to a temporary file in {@code <game directory>/grug_mod_files/.tmp/}, which
     * is on the same filesystem as every mod's directory but in none of them, and are renamed over
     * the target. The bytes are flushed to the disk before the rename and the target's directory
     * after it where the platform can flush one, so a crash or a power loss leaves either the old
     * content or the new one, and the only trace a crash can leave is a file under {@code .tmp}
     * that no read, listing or byte count ever sees. The cap check, the temporary write and the
     * move hold {@link #WRITE_LOCK}, so a write from the render thread and one from the server
     * thread cannot both pass the cap and land.
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
        Path shipped = shippedRoot(modsDir, modName);
        synchronized (WRITE_LOCK) {
            Path target = resolve(gameDir.toPath(), root, path, false);
            requirePortableCase(root, shipped, target, path);
            // The encoded length is counted rather than encoded, so a text over the cap is refused
            // without a second array of its own size ever existing.
            long encoded = utf8Length(text);
            long previous = chargeOf(target);
            long after =
                    totalBytes(root)
                            - previous
                            + charge(encoded)
                            + pendingDirectories(root, target);
            if (after > CAP) {
                throw new IllegalArgumentException(
                        "Writing "
                                + quote(path)
                                + " would charge the mod's "
                                + DIRECTORY
                                + " directory "
                                + after
                                + " bytes, over the "
                                + CAP
                                + "-byte cap.");
            }

            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(target.getParent());
            Path tempDir = tempRoot(gameDir);
            Files.createDirectories(tempDir);
            Path temp = Files.createTempFile(tempDir, TEMP_PREFIX, TEMP_SUFFIX);
            boolean moved = false;
            try {
                Files.write(temp, bytes);
                forceFile(temp);
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                moved = true;
                forceDirectory(target.getParent());
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temp);
                }
            }
        }
    }

    /**
     * What an entry of that size is charged against {@link #CAP}: at least one filesystem block.
     */
    private static long charge(long size) {
        return Math.max(size, BLOCK);
    }

    /**
     * The charge of the existing regular file {@code path}, or 0 when nothing regular is there. The
     * attributes are read without following a link, since {@link #resolve} has already refused one.
     */
    private static long chargeOf(Path path) throws IOException {
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isRegularFile() ? charge(attrs.size()) : 0;
        } catch (NoSuchFileException e) {
            return 0;
        }
    }

    /**
     * The block charge of every directory a write of {@code target} must create below {@code root},
     * so the cap counts them before they exist. Every missing component is charged, not only those
     * above the first one that exists, because a directory a write creates costs a block whatever
     * sits above it. The root itself is not an entry of the cap.
     */
    private static long pendingDirectories(Path root, Path target) {
        long pending = 0;
        Path here = root;
        for (Path part : root.relativize(target.getParent())) {
            if (part.toString().isEmpty()) {
                continue; // the target sits in the root itself, whose own block is not charged
            }
            here = here.resolve(part);
            if (!Files.exists(here, LinkOption.NOFOLLOW_LINKS)) {
                pending += BLOCK;
            }
        }
        return pending;
    }

    /**
     * The bytes {@code text} takes as UTF-8, counted without encoding it, so the cap can refuse a
     * text before a byte array of its own size exists. It matches {@link String#getBytes} including
     * its one-byte replacement of an unpaired surrogate.
     */
    static long utf8Length(String text) {
        long length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                length += 1;
            } else if (c < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                length += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                length += 1; // an unpaired surrogate is encoded as "?"
            } else {
                length += 3;
            }
        }
        return length;
    }

    /** Flushes the temporary file's bytes to the disk before the rename can put it in place. */
    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * Flushes the target's directory after the rename, where the platform can open a directory to
     * flush it.
     *
     * <p>Windows refuses to open a directory, and no Java call flushes one there. That is not a
     * write failure: the file's own bytes were flushed before the rename, so the target still holds
     * the old text or the new one and only the rename's own survival is weaker. A path that no
     * longer exists takes the same arm.
     */
    static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            return;
        }
    }

    /**
     * Refuses a path whose segment differs only in case from an entry the call would answer from,
     * on a write, a read, an exists or a listing alike: on Windows that entry is the file the path
     * names and on this system it is another one, so refusing is what makes one path mean the same
     * thing on every loader.
     *
     * <p>At each level the written copy decides: when it already holds an entry of exactly that
     * name, the path is that entry and a shipped name differing only in case is shadowed by it.
     * Otherwise a name differing only in case from an entry of either tree is refused, and a tree
     * with no entry of that name takes no further part in the walk.
     */
    private static void requirePortableCase(Path writable, Path shipped, Path target, String path)
            throws IOException {
        Path writtenHere = writable;
        Path shippedHere = shipped;
        boolean writtenLive = true;
        boolean shippedLive = true;
        for (Path part : writable.relativize(target)) {
            String name = part.toString();
            if (name.isEmpty()) {
                continue;
            }
            Path writtenChild = writtenHere.resolve(name);
            Path shippedChild = shippedHere.resolve(name);
            boolean writtenExact =
                    writtenLive && Files.exists(writtenChild, LinkOption.NOFOLLOW_LINKS);
            boolean shippedExact =
                    shippedLive && Files.exists(shippedChild, LinkOption.NOFOLLOW_LINKS);
            if (!writtenExact) {
                String clash = writtenLive ? caseClash(writtenHere, name) : null;
                if (clash == null && shippedLive) {
                    clash = caseClash(shippedHere, name);
                }
                if (clash != null) {
                    throw new IllegalArgumentException(
                            "The path "
                                    + quote(path)
                                    + " differs only in case from \""
                                    + clash
                                    + "\", which is one file on Windows and two on this"
                                    + " system, so it is not a portable file name.");
                }
            }
            writtenHere = writtenChild;
            writtenLive = writtenExact;
            shippedHere = shippedChild;
            shippedLive = shippedExact;
        }
    }

    /** The entry of {@code directory} that equals {@code name} ignoring case but not exactly. */
    private static String caseClash(Path directory, String name) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        // Closed by hand rather than by try-with-resources: the compiler's null check around an
        // implicit close adds a branch no call can enter, because Files.newDirectoryStream never
        // answers null.
        DirectoryStream<Path> entries = Files.newDirectoryStream(directory);
        try {
            for (Path entry : entries) {
                String existing = entry.getFileName().toString();
                if (!existing.equals(name) && existing.equalsIgnoreCase(name)) {
                    return existing;
                }
            }
        } finally {
            entries.close();
        }
        return null;
    }

    /**
     * The charged bytes of the tree under {@code root}, which is what the cap counts: one block per
     * directory and per file that holds less, a larger file's own size, and nothing for a link,
     * which is never followed, so no link can feed the cap a target's size.
     */
    private static long totalBytes(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        final long[] total = {0};
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attrs) {
                        if (!directory.equals(root)) {
                            total[0] += BLOCK;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) {
                            total[0] += charge(attrs.size());
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
