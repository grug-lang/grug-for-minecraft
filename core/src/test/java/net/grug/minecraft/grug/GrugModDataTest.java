package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Covers the one directory a mod may write: its paths, its reads, its cap and its atomic write. */
class GrugModDataTest {

    @TempDir Path tmp;

    private File gameDir() throws IOException {
        return Files.createDirectories(tmp.resolve("game")).toFile();
    }

    private File modsDir() throws IOException {
        return Files.createDirectories(tmp.resolve("mods")).toFile();
    }

    private Path writable(String mod) throws IOException {
        return GrugModData.writableRoot(gameDir(), mod);
    }

    private Path shipped(String mod) throws IOException {
        return GrugModData.shippedRoot(modsDir(), mod);
    }

    private String read(String mod, String path) throws IOException {
        return GrugModData.read(modsDir(), gameDir(), mod, path);
    }

    private void write(String mod, String path, String text) throws IOException {
        GrugModData.write(modsDir(), gameDir(), mod, path, text);
    }

    private long bytesUnder(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(
                            path -> {
                                try {
                                    return Files.size(path);
                                } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            })
                    .sum();
        }
    }

    @Test
    void rootsSitBesideTheConfigAsADirectoryPerMod() throws IOException {
        assertEquals(
                gameDir()
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .resolve("grug_mod_files")
                        .resolve("coverage")
                        .resolve("files"),
                writable("coverage"));
        assertEquals(
                modsDir()
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .resolve("coverage")
                        .resolve("files"),
                shipped("coverage"));
    }

    @Test
    void modComesFromTheCompilersRewriteOfTheResource() {
        assertEquals("coverage", GrugModData.modOf("coverage/files"));
        assertEquals("a-mod", GrugModData.modOf("a-mod/files"));
    }

    @Test
    void aResourceThatNamesNoModFilesDirectoryIsRefused() {
        for (String resource :
                new String[] {
                    null,
                    "",
                    "files",
                    "/files",
                    "coverage/",
                    "coverage/assets/crafting.png",
                    "./files",
                    "../files",
                    "both/../files",
                    "co:verage/files",
                    "cov\\erage/files",
                    "cov\0erage/files",
                    "a/b/files"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GrugModData.modOf(resource),
                    "the resource " + resource);
        }
    }

    @Test
    void aPathIsPlainRelativeAndNeverClimbs() {
        for (String path :
                new String[] {
                    null,
                    "",
                    "a\\b",
                    "a\0b",
                    "a:b",
                    "a*b",
                    "a?b",
                    "a\"b",
                    "a<b",
                    "a>b",
                    "a|b",
                    "a\tb",
                    "name.",
                    "name ",
                    "CON",
                    "con.txt",
                    "AUX",
                    "NUL",
                    "COM1",
                    "lpt9.tar.gz",
                    "/absolute",
                    "a//b",
                    "./",
                    "a/./b",
                    ".",
                    "..",
                    "a/..",
                    "../a",
                    "/a",
                    "a/"
                }) {
            assertTrue(GrugModData.pathProblem(path, false) != null, "the path " + path);
        }
        assertNull(GrugModData.pathProblem("a/b.txt", false));
        assertNull(GrugModData.pathProblem("a.txt", false));
        assertNull(GrugModData.pathProblem("a.b", false));
        assertNull(GrugModData.pathProblem("a b.txt", false));
        assertNull(GrugModData.pathProblem("console.txt", false));
        assertNull(GrugModData.pathProblem("com0", false));
        assertNull(GrugModData.pathProblem(".", true));
        assertNull(GrugModData.pathProblem("./a.txt", false));
        assertNull(GrugModData.pathProblem("././a.txt", false));
        assertTrue(GrugModData.pathProblem("./../a", true) != null);
        assertTrue(GrugModData.pathProblem("a/.", true) != null);
    }

    @Test
    void aResolvedPathStaysInsideItsOwnDirectoryAndNeverFollowsALink() throws IOException {
        Path root = writable("coverage");
        Files.createDirectories(root);
        Path anchor = gameDir().toPath();
        Path inside = Files.writeString(root.resolve("inside.txt"), "in");
        assertEquals(
                root.resolve("inside.txt"), GrugModData.resolve(anchor, root, "inside.txt", false));
        assertEquals(
                root.resolve("not/yet.txt"),
                GrugModData.resolve(anchor, root, "not/yet.txt", false));
        assertEquals(root, GrugModData.resolve(anchor, root, ".", true));
        assertEquals(
                root.resolve("inside.txt"),
                GrugModData.resolve(anchor, root, "./inside.txt", false));

        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("escape"), outside);
        for (String path : new String[] {"escape", "escape/secret.txt"}) {
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> GrugModData.resolve(anchor, root, path, false),
                            path);
            assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        }

        Files.createSymbolicLink(root.resolve("inside-link.txt"), inside);
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.resolve(anchor, root, "inside-link.txt", false));

        Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("missing"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.resolve(anchor, root, "dangling", false));

        Path linkedRoot = writable("linked");
        Files.createDirectories(linkedRoot.getParent());
        Files.createSymbolicLink(linkedRoot, root);
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.resolve(anchor, linkedRoot, "a.txt", false));

        Path notUnder = Files.createDirectories(tmp.resolve("elsewhere"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.resolve(anchor, notUnder, "a.txt", false));
    }

    @Test
    void aLinkAboveTheModsDirectoryIsRefusedToo() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path tree = gameDir().toPath().resolve(GrugModData.WRITABLE_DIRECTORY);
        Files.createSymbolicLink(tree, outside);
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> write("coverage", "a.txt", "x"));
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        assertFalse(Files.exists(outside.resolve("coverage")));
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "a.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.exists(modsDir(), gameDir(), "coverage", "a.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.entryCount(modsDir(), gameDir(), "coverage", "."));
    }

    @Test
    void aLinkAtTheModsDirectoryIsRefused() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path mod = gameDir().toPath().resolve(GrugModData.WRITABLE_DIRECTORY).resolve("coverage");
        Files.createDirectories(mod.getParent());
        Files.createSymbolicLink(mod, outside);
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> write("coverage", "a.txt", "x"));
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        assertFalse(Files.exists(outside.resolve("files")));
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "a.txt"));

        // A mod with no writable tree at all, so the shipped walk is the one that meets the link:
        // the writable side of the mod above would otherwise refuse the path first.
        Path shippedMod = modsDir().toPath().resolve("shippedlink");
        Files.createSymbolicLink(shippedMod, outside);
        IllegalArgumentException shipped =
                assertThrows(IllegalArgumentException.class, () -> read("shippedlink", "a.txt"));
        assertTrue(shipped.getMessage().contains("symbolic link"), shipped.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.exists(modsDir(), gameDir(), "shippedlink", "a.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.entryCount(modsDir(), gameDir(), "shippedlink", "."));
    }

    @Test
    void aBrokenPathIsReportedWithItsName() throws IOException {
        Path anchor = gameDir().toPath();
        Path root = writable("coverage");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugModData.resolve(anchor, root, null, false));
        assertTrue(e.getMessage().contains("(no path)"), e.getMessage());
        e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugModData.resolve(anchor, root, "a:b", false));
        assertTrue(e.getMessage().contains("\"a:b\""), e.getMessage());
    }

    @Test
    void readPrefersTheWritableCopyAndFallsBackToTheShippedFile() throws IOException {
        Path writable = writable("coverage");
        Path shipped = shipped("coverage");
        Files.createDirectories(writable);
        Files.createDirectories(shipped);
        Files.writeString(shipped.resolve("default.txt"), "shipped");
        Files.writeString(writable.resolve("own.txt"), "written");
        Files.writeString(shipped.resolve("both.txt"), "shipped");
        Files.writeString(writable.resolve("both.txt"), "written");

        assertEquals("written", read("coverage", "own.txt"));
        assertEquals("shipped", read("coverage", "default.txt"));
        assertEquals("written", read("coverage", "both.txt"));
        assertNull(read("coverage", "absent.txt"));

        Files.createDirectories(writable.resolve("dir"));
        Files.writeString(writable.resolve("dir/child.txt"), "child");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> read("coverage", "dir"));
        assertTrue(e.getMessage().contains("names a directory"), e.getMessage());

        Files.createDirectories(shipped.resolve("shipdir"));
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "shipdir"));

        Files.createDirectories(writable.resolve("blocked.txt"));
        Files.writeString(shipped.resolve("blocked.txt"), "shipped");
        IllegalArgumentException blocked =
                assertThrows(IllegalArgumentException.class, () -> read("coverage", "blocked.txt"));
        assertTrue(blocked.getMessage().contains("names a directory"), blocked.getMessage());
        assertEquals(0, GrugModData.entryCount(modsDir(), gameDir(), "coverage", "blocked.txt"));

        // The written copy decides the other way too: a written file wins over a shipped directory.
        Files.createDirectories(shipped.resolve("shippeddir"));
        Files.writeString(writable.resolve("shippeddir"), "written file");
        assertEquals("written file", read("coverage", "shippeddir"));
        e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                GrugModData.entryCount(
                                        modsDir(), gameDir(), "coverage", "shippeddir"));
        assertTrue(e.getMessage().contains("names a file"), e.getMessage());

        Files.createDirectories(writable.resolve("bad"));
        Files.write(writable.resolve("bad/text.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});
        e = assertThrows(IllegalArgumentException.class, () -> read("coverage", "bad/text.txt"));
        assertTrue(e.getMessage().contains("not UTF-8"), e.getMessage());
    }

    @Test
    void aMissingFileIsEmptyAndADirectoryIsStillSomething() throws IOException {
        assertFalse(GrugModData.exists(modsDir(), gameDir(), "coverage", "absent.txt"));
        Files.createDirectories(shipped("coverage"));
        Files.writeString(shipped("coverage").resolve("shipped.txt"), "s");
        assertTrue(GrugModData.exists(modsDir(), gameDir(), "coverage", "shipped.txt"));
        Files.createDirectories(writable("coverage"));
        Files.writeString(writable("coverage").resolve("own.txt"), "o");
        assertTrue(GrugModData.exists(modsDir(), gameDir(), "coverage", "own.txt"));
        assertTrue(GrugModData.exists(modsDir(), gameDir(), "coverage", "own.txt"));
    }

    @Test
    void aListingShowsBothTreesSortedAndOncePerName() throws IOException {
        Path writable = writable("coverage");
        Path shipped = shipped("coverage");
        Files.createDirectories(writable);
        Files.createDirectories(shipped);
        Files.writeString(shipped.resolve("b.txt"), "s");
        Files.writeString(shipped.resolve("a.txt"), "s");
        Files.writeString(writable.resolve("c.txt"), "w");
        Files.writeString(writable.resolve("a.txt"), "w");

        assertEquals(3, GrugModData.entryCount(modsDir(), gameDir(), "coverage", "."));
        assertEquals("a.txt", GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 0));
        assertEquals("b.txt", GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 1));
        assertEquals("c.txt", GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 2));

        assertEquals(0, GrugModData.entryCount(modsDir(), gameDir(), "coverage", "missing"));
        assertEquals(0, GrugModData.entryCount(modsDir(), gameDir(), "other", "."));

        Files.writeString(writable.resolve("one.txt"), "w");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugModData.entryCount(modsDir(), gameDir(), "coverage", "one.txt"));
        assertTrue(e.getMessage().contains("names a file"), e.getMessage());

        Files.writeString(shipped.resolve("shipped-only.txt"), "s");
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.entryCount(modsDir(), gameDir(), "coverage", "shipped-only.txt"));
    }

    @Test
    void aLinkIsNotListedBecauseNoReadCanOpenIt() throws IOException {
        Path writable = Files.createDirectories(writable("coverage"));
        Path shipped = Files.createDirectories(shipped("coverage"));
        Files.writeString(writable.resolve("real.txt"), "real");
        Files.writeString(shipped.resolve("shipped.txt"), "shipped");
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "secret");
        Files.createSymbolicLink(writable.resolve("wlink"), outside);
        Files.createSymbolicLink(shipped.resolve("slink"), outside);

        assertEquals(2, GrugModData.entryCount(modsDir(), gameDir(), "coverage", "."));
        assertEquals("real.txt", GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 0));
        assertEquals(
                "shipped.txt", GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.exists(modsDir(), gameDir(), "coverage", "wlink"));
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "wlink"));
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "slink"));
    }

    @Test
    void aListingIndexIsAWholeNumberInsideTheList() throws IOException {
        Files.createDirectories(shipped("coverage"));
        Files.writeString(shipped("coverage").resolve("a.txt"), "s");
        for (double index : new double[] {Double.NaN, 1.5, -1, 3e9}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", index));
        }
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 1));
        assertTrue(e.getMessage().contains("holds 1"), e.getMessage());
    }

    @Test
    void aWriteCreatesItsDirectoriesAndReplacesThePreviousTextOutsideTheTree() throws IOException {
        write("coverage", "one/two/state.txt", "first");
        assertEquals("first", read("coverage", "one/two/state.txt"));
        write("coverage", "one/two/state.txt", "second");
        assertEquals("second", read("coverage", "one/two/state.txt"));

        assertEquals(List.of("state.txt"), names(writable("coverage").resolve("one/two")));
        assertEquals(List.of(), names(GrugModData.tempRoot(gameDir())));
        assertEquals(
                List.of(".tmp", "coverage"),
                names(gameDir().toPath().resolve(GrugModData.WRITABLE_DIRECTORY)));
    }

    @Test
    void aPathThatDiffersOnlyInCaseFromAnEntryIsRefusedByEveryCall() throws IOException {
        write("coverage", "Case.txt", "first");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> write("coverage", "case.txt", "s"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());
        assertEquals("first", read("coverage", "Case.txt"));
        // A read and an exists refuse the name as well: the write contract would be worthless if
        // one system answered the case-folded name and another did not.
        e = assertThrows(IllegalArgumentException.class, () -> read("coverage", "case.txt"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());
        e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugModData.exists(modsDir(), gameDir(), "coverage", "CASE.TXT"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());

        Files.createDirectories(shipped("coverage"));
        Files.writeString(shipped("coverage").resolve("Shipped.txt"), "s");
        e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> write("coverage", "shipped.txt", "w"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> read("coverage", "shipped.txt"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.exists(modsDir(), gameDir(), "coverage", "SHIPPED.TXT"));

        write("coverage", "Sub/one.txt", "x");
        e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> write("coverage", "sub/two.txt", "y"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());
        assertFalse(Files.exists(writable("coverage").resolve("sub")));
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "sub/two.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.entryCount(modsDir(), gameDir(), "coverage", "SUB"));
    }

    @Test
    void theCapIsPerModAndAFailedWriteKeepsTheOldText() throws IOException {
        Path root = writable("coverage");
        Files.createDirectories(root);
        Path big = root.resolve("big.bin");
        Files.write(big, new byte[(int) GrugModData.CAP - 1]);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> write("coverage", "small.txt", "ab"));
        assertTrue(
                e.getMessage().contains(String.valueOf(GrugModData.CAP - 1 + GrugModData.BLOCK)),
                e.getMessage());
        assertTrue(e.getMessage().contains("\"small.txt\""), e.getMessage());
        assertFalse(Files.exists(root.resolve("small.txt")));
        assertEquals(GrugModData.CAP - 1, Files.size(big));

        write("coverage", "big.bin", "small");
        assertEquals("small", read("coverage", "big.bin"));
    }

    @Test
    void tinyFilesAreChargedOneBlockEachSoTheCapBoundsTheEntryCount() throws IOException {
        Path root = Files.createDirectories(writable("coverage"));
        // The charge is the filesystem's own accounting, not a guess about the mod: it spends a
        // block on every file whatever the file's size says, and one on every directory, so a mod
        // cannot fill the disk with 10000 one-byte files while its byte count stays tiny.
        for (int i = 0; i < GrugModData.CAP / GrugModData.BLOCK; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "x");
        }

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> write("coverage", "overflow.txt", "x"));
        assertTrue(
                e.getMessage().contains(String.valueOf(GrugModData.CAP + GrugModData.BLOCK)),
                e.getMessage());
        assertFalse(Files.exists(root.resolve("overflow.txt")));
    }

    @Test
    void aListingNamesOnlyEntriesAReadAccepts() throws IOException {
        Path writable = Files.createDirectories(writable("coverage"));
        Path shipped = Files.createDirectories(shipped("coverage"));
        Files.writeString(shipped.resolve("ok.txt"), "s");
        // A name the host refuses is legal on the Linux runner, so only the listing's own filter
        // keeps it out: it must never return a name that read_text refuses.
        Files.writeString(shipped.resolve("space.txt "), "s");

        assertEquals(1, GrugModData.entryCount(modsDir(), gameDir(), "coverage", "."));
        assertEquals("ok.txt", GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 0));
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "space.txt "));

        // A written name hides a shipped name it differs from only in case, so the one the listing
        // names is the one a read answers.
        Files.writeString(writable.resolve("Dup.txt"), "written");
        Files.writeString(shipped.resolve("dup.txt"), "shipped");
        Files.writeString(shipped.resolve("DUP.txt"), "shipped");
        Files.createDirectories(writable.resolve("folder"));
        assertEquals(3, GrugModData.entryCount(modsDir(), gameDir(), "coverage", "."));
        assertEquals("Dup.txt", GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 0));
        assertEquals("folder", GrugModData.entryName(modsDir(), gameDir(), "coverage", ".", 1));
        assertEquals("written", read("coverage", "Dup.txt"));
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "dup.txt"));
    }

    @Test
    void directoriesBelowAnExistingOneAreChargedToo() throws IOException {
        write("coverage", "a/x.txt", "kept");
        Path root = writable("coverage");
        Files.write(root.resolve("fill.bin"), new byte[12 * 1024 * 1024]);

        StringBuilder path = new StringBuilder("a");
        for (int i = 0; i < 1500; i++) {
            path.append("/b");
        }
        path.append("/c.txt");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> write("coverage", path.toString(), "x"));
        assertTrue(e.getMessage().contains("over the " + GrugModData.CAP), e.getMessage());
        assertFalse(
                Files.exists(root.resolve("a").resolve("b")),
                "a refused write should create no directory");

        assertEquals("kept", read("coverage", "a/x.txt"));
        // Nothing was created, so the mod is not charged for the refused attempt and still writes.
        write("coverage", "later.txt", "fine");
        assertEquals("fine", read("coverage", "later.txt"));
    }

    @Test
    void theUtf8LengthIsCountedWithoutEncodingTheText() {
        for (String text :
                new String[] {
                    "",
                    "a",
                    "caf\u00e9",
                    "\u20ac",
                    "\ud83d\ude00",
                    "a\ud83d\ude00b",
                    "\ud800",
                    "\ud800a",
                    "trailing\udc00",
                    "mixed \u00e9\u20ac\ud83d\ude00"
                }) {
            assertEquals(
                    text.getBytes(StandardCharsets.UTF_8).length,
                    GrugModData.utf8Length(text),
                    text);
        }
    }

    @Test
    void theCapCountsBytesNotCharactersSoTextOverItIsRefusedBeforeItIsEncoded() throws IOException {
        // Three bytes per character: the text is under the cap in characters and over it in bytes.
        long characters = GrugModData.CAP / 3 + 1;
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> write("coverage", "euro.txt", "\u20ac".repeat((int) characters)));
        assertTrue(e.getMessage().contains(String.valueOf(characters * 3)), e.getMessage());
        assertFalse(Files.exists(writable("coverage").resolve("euro.txt")));

        write("coverage", "euro.txt", "\u20ac".repeat((int) characters - 1));
        assertEquals(characters * 3 - 3, GrugModData.utf8Length(read("coverage", "euro.txt")));
    }

    @Test
    void theRefusedSizeIsCountedWithoutEncodingTheText() throws IOException {
        // The cap must refuse an over-cap text without a byte array of its own size: the check
        // counts
        // the bytes, and only a write that passes the cap encodes them.
        String big = "x".repeat(17 * 1024 * 1024);
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean)
                        java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled(),
                "this JVM does not count a thread's own allocations");
        long before = bean.getCurrentThreadAllocatedBytes();
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> write("coverage", "big.txt", big));
        long allocated = bean.getCurrentThreadAllocatedBytes() - before;
        assertTrue(e.getMessage().contains("over the " + GrugModData.CAP), e.getMessage());
        assertTrue(
                allocated < big.length() / 2,
                "the refusal allocated "
                        + allocated
                        + " bytes for a "
                        + big.length()
                        + "-byte text, so it encoded the text before checking the cap");
    }

    @Test
    void anEntryThatIsNeitherFileNorDirectoryIsRefusedAndNeverListed() throws IOException {
        // A Windows directory junction is reported as neither a link nor a regular file but as
        // "other", which is the arm that catches it there; a Unix domain socket is that same kind
        // of
        // entry here, so this test covers the arm a Linux run can reach.
        Path root = Files.createDirectories(writable("coverage"));
        Path special = root.resolve("special");
        try (java.nio.channels.ServerSocketChannel server =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(java.net.UnixDomainSocketAddress.of(special));
            IllegalArgumentException e =
                    assertThrows(IllegalArgumentException.class, () -> read("coverage", "special"));
            assertTrue(e.getMessage().contains("symbolic link or a junction"), e.getMessage());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GrugModData.exists(modsDir(), gameDir(), "coverage", "special"));
            assertEquals(0, GrugModData.entryCount(modsDir(), gameDir(), "coverage", "."));
        }
    }

    @Test
    void aDirectoryThatCannotBeOpenedForAFlushIsNotAWriteFailure() {
        // A platform that refuses to open a directory takes the catch arm; a path that is not
        // there takes the same one here, and the write itself has already flushed the file.
        GrugModData.forceDirectory(tmp.resolve("no-such-directory"));
    }

    @Test
    void aWriteTheFilesystemRefusesLeavesWhatWasThereAndNoTemporaryFile() throws IOException {
        Path root = writable("coverage");
        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("dir/child.txt"), "kept");

        assertThrows(IOException.class, () -> write("coverage", "dir", "text"));

        assertEquals("kept", Files.readString(root.resolve("dir/child.txt")));
        assertEquals(List.of("dir"), names(root));
        assertEquals(List.of(), names(GrugModData.tempRoot(gameDir())));
    }

    @Test
    void aLinkIsRefusedAndTheCapNeverCountsWhatItPointsAt() throws IOException {
        Path root = writable("coverage");
        Files.createDirectories(root);
        Path big = Files.write(tmp.resolve("outside.bin"), new byte[(int) GrugModData.CAP]);
        Files.createSymbolicLink(root.resolve("link.bin"), big);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> write("coverage", "link.bin", "x"));
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> read("coverage", "link.bin"));

        // The target is not counted, so a new file still fits under the cap.
        write("coverage", "small.txt", "fine");
        assertEquals("fine", read("coverage", "small.txt"));
        assertTrue(bytesUnder(root) < GrugModData.CAP);
    }

    @Test
    void twoWritesAtOnceCannotBothPassTheCap() throws Exception {
        String nine = "x".repeat(9 * 1024 * 1024);
        for (int round = 0; round < 5; round++) {
            String mod = "race" + round;
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
                for (String name : new String[] {"a.bin", "b.bin"}) {
                    results.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        try {
                                            GrugModData.write(
                                                    modsDir(), gameDir(), mod, name, nine);
                                            return true;
                                        } catch (IllegalArgumentException e) {
                                            return false;
                                        }
                                    }));
                }
                start.countDown();
                int written = 0;
                for (java.util.concurrent.Future<Boolean> result : results) {
                    written += result.get() ? 1 : 0;
                }
                assertEquals(1, written, "round " + round);
                assertTrue(bytesUnder(writable(mod)) <= GrugModData.CAP, "round " + round);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aHandleThatNamesNoModTouchesNothing() throws IOException {
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.read(modsDir(), gameDir(), null, "a.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.exists(modsDir(), gameDir(), null, "a.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.entryCount(modsDir(), gameDir(), null, "."));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.entryName(modsDir(), gameDir(), null, ".", 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.write(modsDir(), gameDir(), null, "a.txt", "x"));
        assertFalse(Files.exists(gameDir().toPath().resolve(GrugModData.WRITABLE_DIRECTORY)));
    }

    private List<String> names(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> list = Files.list(directory)) {
            list.forEach(path -> names.add(path.getFileName().toString()));
        }
        names.sort(null);
        return names;
    }

    @Test
    void textIsWrittenAsUtf8() throws IOException {
        write("coverage", "name.txt", "caf\u00e9 \u20ac \ud83d\ude00");
        assertEquals(
                "caf\u00e9 \u20ac \ud83d\ude00",
                new String(
                        Files.readAllBytes(writable("coverage").resolve("name.txt")),
                        StandardCharsets.UTF_8));
    }
}
