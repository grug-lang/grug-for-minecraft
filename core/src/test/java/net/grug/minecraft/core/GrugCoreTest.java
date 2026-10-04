package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Covers the startup work in {@link GrugCore#initialize}. */
class GrugCoreTest {

    @TempDir Path mods;

    /**
     * An adapter that records what it was asked to log and does nothing else, which is all the
     * startup path before {@code Grug.init} reaches for the native library.
     */
    private ModLoaderAdapter recordingAdapter(List<String> log) {
        return adapter(log, GrugSide.CLIENT);
    }

    /** The same, with the one answer a startup cannot do without being told which side it is on. */
    private ModLoaderAdapter adapter(List<String> log, GrugSide side) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("logInfo")) {
                                log.add(String.valueOf(args[0]));
                            }
                            if (method.getName().equals("getSide")) {
                                return side;
                            }
                            return null;
                        });
    }

    @Test
    void initializeRejectsAModWithoutALicense() throws Exception {
        Files.createDirectories(mods.resolve("mymod"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        GrugCore.initialize(
                                recordingAdapter(new ArrayList<>()),
                                new File("mod_api.json"),
                                mods.toFile()));
    }

    @Test
    void initializeRejectsAnAdapterThatCannotSayWhichSideItIs() {
        // A loader that forgot the new method answers null, and every level or player lookup would
        // then fail much later as a missing player. Startup is where the cause is still nameable.
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GrugCore.initialize(
                                        adapter(new ArrayList<>(), null),
                                        new File("mod_api.json"),
                                        mods.toFile()));

        assertTrue(error.getMessage().contains("which side"), error.getMessage());
    }

    @Test
    void initializeRestoresWhatAnEarlierRunLeftDisturbed() throws Exception {
        Path code = Files.createDirectories(mods.resolve("mymod/code"));
        Path file = code.resolve("thing-Block.grug");
        Files.write(file, "!\nexport init() {\n}\n".getBytes("UTF-8"));
        // The record the run that marked the file left behind.
        Path record =
                mods.resolveSibling(".grug_test_backups")
                        .resolve("mymod/code/thing-Block.grug.grugbak");
        Files.createDirectories(record.getParent());
        Files.write(record, "export init() {\n}\n".getBytes("UTF-8"));
        List<String> log = new ArrayList<>();

        // The license gate stops the call right after the restore, which is all this asserts.
        assertThrows(
                IllegalStateException.class,
                () ->
                        GrugCore.initialize(
                                recordingAdapter(log), new File("mod_api.json"), mods.toFile()));

        assertEqualsText("export init() {\n}\n", file);
        assertTrue(Files.notExists(record.getParent()), "the record is spent");
        assertTrue(
                log.stream().anyMatch(line -> line.contains("mymod/code/thing-Block.grug")),
                log.toString());
    }

    @Test
    void initializeStopsWhenItCannotRestore() throws Exception {
        // A directory where the file belongs, so the restore reads the record and cannot write.
        Path file = Files.createDirectories(mods.resolve("mymod/code/thing-Block.grug"));
        Path record =
                mods.resolveSibling(".grug_test_backups")
                        .resolve("mymod/code/thing-Block.grug.grugbak");
        Files.createDirectories(record.getParent());
        Files.write(record, "export init() {\n}\n".getBytes("UTF-8"));
        List<String> log = new ArrayList<>();

        // A complaint about the record, not the license a mod is missing, because that is what
        // actually went wrong first.
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GrugCore.initialize(
                                        recordingAdapter(log),
                                        new File("mod_api.json"),
                                        mods.toFile()));

        assertTrue(error.getMessage().contains("test state"), error.getMessage());
    }

    private static void assertEqualsText(String expected, Path actual) throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(expected, Files.readString(actual));
    }
}
