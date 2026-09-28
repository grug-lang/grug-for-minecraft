package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Covers the {@code screenshots/} tree convention enforced by {@link
 * GrugScreenshots#validateReferenceTrees(java.io.File)}.
 */
class GrugScreenshotsTest {

    @TempDir Path mods;

    private void touch(String relativePath) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.createFile(path);
    }

    private List<String> validate() {
        return GrugScreenshots.validateReferenceTrees(mods.toFile());
    }

    private void assertSingleErrorContaining(String fragment) {
        List<String> errors = validate();
        assertEquals(1, errors.size(), () -> "expected exactly one problem, got " + errors);
        assertTrue(errors.get(0).contains(fragment), errors.get(0));
    }

    @Test
    void acceptsDenseOneBasedReferences() throws IOException {
        touch("mymod/screenshots/table/1.png");
        touch("mymod/screenshots/table/2.png");
        touch("mymod/screenshots/table/3.png");
        assertEquals(List.of(), validate());
    }

    @Test
    void acceptsNestedGroups() throws IOException {
        touch("mymod/screenshots/gui/table/1.png");
        touch("mymod/screenshots/gui/chest/1.png");
        assertEquals(List.of(), validate());
    }

    @Test
    void acceptsScreenshotsDirectoryItselfAsAReference() throws IOException {
        touch("mymod/screenshots/1.png");
        assertEquals(List.of(), validate());
    }

    @Test
    void ignoresModsWithoutAScreenshotsDirectory() throws IOException {
        touch("mymod/data/thing.txt");
        assertEquals(List.of(), validate());
    }

    @Test
    void rejectsZeroAsAReferenceNumber() throws IOException {
        touch("mymod/screenshots/table/0.png");
        assertSingleErrorContaining("0.png");
    }

    @Test
    void rejectsALeadingZero() throws IOException {
        touch("mymod/screenshots/table/01.png");
        assertSingleErrorContaining("01.png");
    }

    @Test
    void rejectsAnUppercaseExtension() throws IOException {
        touch("mymod/screenshots/table/1.PNG");
        assertSingleErrorContaining("1.PNG");
    }

    @Test
    void rejectsANonPngFile() throws IOException {
        touch("mymod/screenshots/table/1.png");
        touch("mymod/screenshots/table/README.md");
        assertSingleErrorContaining("README.md");
    }

    @Test
    void rejectsAGapInTheNumbering() throws IOException {
        touch("mymod/screenshots/table/1.png");
        touch("mymod/screenshots/table/3.png");
        assertSingleErrorContaining("missing 2.png");
    }

    @Test
    void rejectsMixingReferencesWithASubdirectory() throws IOException {
        touch("mymod/screenshots/table/1.png");
        touch("mymod/screenshots/table/sub/1.png");
        List<String> errors = validate();
        assertTrue(errors.stream().anyMatch(error -> error.contains("mixes")), errors.toString());
    }

    @Test
    void reportsAnUnparseablyLargeNumberInsteadOfThrowing() throws IOException {
        touch("mymod/screenshots/table/9999999999.png");
        assertSingleErrorContaining("9999999999.png");
    }

    @Test
    void reportsEveryViolationAcrossMods() throws IOException {
        touch("one/screenshots/table/0.png");
        touch("two/screenshots/chest/1.png");
        touch("two/screenshots/chest/3.png");
        List<String> errors = validate();
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("one/screenshots")),
                errors.toString());
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("two/screenshots")),
                errors.toString());
    }

    @Test
    void treatsAMissingModsDirectoryAsEmpty() {
        assertEquals(
                List.of(), GrugScreenshots.validateReferenceTrees(mods.resolve("nope").toFile()));
    }
}
