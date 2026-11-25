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
                    "./a",
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
        assertTrue(GrugModData.pathProblem("./a", true) != null);
        assertTrue(GrugModData.pathProblem("a/.", true) != null);
    }

    @Test
    void aResolvedPathStaysInsideItsOwnDirectoryAndNeverFollowsALink() throws IOException {
        Path root = writable("coverage");
        Files.createDirectories(root);
        Path inside = Files.writeString(root.resolve("inside.txt"), "in");
        assertEquals(root.resolve("inside.txt"), GrugModData.resolve(root, "inside.txt", false));
        assertEquals(root.resolve("not/yet.txt"), GrugModData.resolve(root, "not/yet.txt", false));
        assertEquals(root, GrugModData.resolve(root, ".", true));

        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("escape"), outside);
        for (String path : new String[] {"escape", "escape/secret.txt"}) {
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> GrugModData.resolve(root, path, false),
                            path);
            assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        }

        Files.createSymbolicLink(root.resolve("inside-link.txt"), inside);
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.resolve(root, "inside-link.txt", false));

        Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("missing"));
        assertThrows(
                IllegalArgumentException.class, () -> GrugModData.resolve(root, "dangling", false));

        Path linkedRoot = writable("linked");
        Files.createDirectories(linkedRoot.getParent());
        Files.createSymbolicLink(linkedRoot, root);
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugModData.resolve(linkedRoot, "a.txt", false));
    }

    @Test
    void aBrokenPathIsReportedWithItsName() throws IOException {
        Path root = writable("coverage");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugModData.resolve(root, null, false));
        assertTrue(e.getMessage().contains("(no path)"), e.getMessage());
        e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugModData.resolve(root, "a:b", false));
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
        assertEquals("shipped", read("coverage", "blocked.txt"));

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
    void aWriteThatDiffersOnlyInCaseFromAnEntryIsRefused() throws IOException {
        write("coverage", "Case.txt", "first");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> write("coverage", "case.txt", "s"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());
        assertEquals("first", read("coverage", "Case.txt"));
        assertNull(read("coverage", "case.txt"));

        Files.createDirectories(shipped("coverage"));
        Files.writeString(shipped("coverage").resolve("Shipped.txt"), "s");
        e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> write("coverage", "shipped.txt", "w"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());

        write("coverage", "Sub/one.txt", "x");
        e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> write("coverage", "sub/two.txt", "y"));
        assertTrue(e.getMessage().contains("differs only in case"), e.getMessage());
        assertFalse(Files.exists(writable("coverage").resolve("sub")));
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
        assertTrue(e.getMessage().contains(String.valueOf(GrugModData.CAP)), e.getMessage());
        assertTrue(e.getMessage().contains("\"small.txt\""), e.getMessage());
        assertFalse(Files.exists(root.resolve("small.txt")));
        assertEquals(GrugModData.CAP - 1, Files.size(big));

        write("coverage", "big.bin", "small");
        assertEquals("small", read("coverage", "big.bin"));
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
