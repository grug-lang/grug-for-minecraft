package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Covers the LICENSE convention enforced by {@link GrugModLicenses#validate(java.io.File)}. */
class GrugModLicensesTest {

    @TempDir Path mods;

    private void write(String relativePath, String content) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private List<String> validate() {
        return GrugModLicenses.validate(mods.toFile());
    }

    private void assertSingleErrorContaining(String fragment) {
        List<String> errors = validate();
        assertEquals(1, errors.size(), () -> "expected exactly one problem, got " + errors);
        assertTrue(errors.get(0).contains(fragment), errors.get(0));
    }

    @Test
    void acceptsAModWithANonEmptyLicense() throws IOException {
        write("mymod/LICENSE", "license text");
        assertEquals(List.of(), validate());
    }

    @Test
    void rejectsAModWithNoLicense() throws IOException {
        write("mymod/about.json", "{}");
        assertSingleErrorContaining("mymod");
    }

    @Test
    void rejectsAnEmptyLicense() throws IOException {
        write("mymod/LICENSE", "");
        assertSingleErrorContaining("mymod");
    }

    @Test
    void ignoresPlainFilesInTheModsDirectory() throws IOException {
        write("stray.txt", "not a mod directory");
        assertEquals(List.of(), validate());
    }

    @Test
    void acceptsAnEmptyModsDirectory() {
        assertEquals(List.of(), validate());
    }

    @Test
    void reportsEveryUnlicensedMod() throws IOException {
        write("one/about.json", "{}");
        write("two/LICENSE", "license text");
        write("three/about.json", "{}");
        List<String> errors = validate();
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("one")), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("three")), errors.toString());
    }

    @Test
    void treatsAnUnreadableModsDirectoryAsEmpty() {
        assertEquals(
                List.of(), GrugModLicenses.validate(new File(mods.toFile(), "does-not-exist")));
    }
}
