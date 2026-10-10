package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Covers the {@code screenshots/} tree convention enforced by {@link
 * GrugScreenshots#validateReferenceTrees(java.io.File)}, and the share-of-pixels-changed measure
 * {@link GrugScreenshots#changedPercent} reports for a test that compares two of its own captures.
 */
class GrugScreenshotsTest {

    /** The name of a loader under {@code loaders/}, so a reference may carry it. */
    private static final String LOADER = "b1.7.3-ornithe";

    /** A second loader name, for a directory that holds two references. */
    private static final String OTHER_LOADER = "1.2.5-forge";

    @TempDir Path mods;

    private void touch(String relativePath) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.createFile(path);
    }

    private void writePng(String relativePath, BufferedImage image) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    private List<String> validate() {
        return GrugScreenshots.validateReferenceTrees(mods.toFile());
    }

    private void assertSingleErrorContaining(String fragment) {
        List<String> errors = validate();
        assertEquals(1, errors.size(), () -> "expected exactly one problem, got " + errors);
        assertTrue(errors.get(0).contains(fragment), errors.get(0));
    }

    /** A solid image, so a test can say which pixels it repainted. */
    private static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    @Test
    void acceptsLoaderNamedReferences() throws IOException {
        writePng("mymod/screenshots/table/" + LOADER + ".png", solid(2, 2, 0x101010));
        writePng("mymod/screenshots/table/" + OTHER_LOADER + ".png", solid(2, 2, 0x202020));
        assertEquals(List.of(), validate());
    }

    @Test
    void acceptsNestedGroups() throws IOException {
        writePng("mymod/screenshots/gui/table/" + LOADER + ".png", solid(2, 2, 0));
        writePng("mymod/screenshots/gui/chest/" + OTHER_LOADER + ".png", solid(2, 2, 0));
        assertEquals(List.of(), validate());
    }

    @Test
    void acceptsScreenshotsDirectoryItselfAsAReference() throws IOException {
        writePng("mymod/screenshots/" + LOADER + ".png", solid(2, 2, 0));
        assertEquals(List.of(), validate());
    }

    @Test
    void ignoresModsWithoutAScreenshotsDirectory() throws IOException {
        touch("mymod/data/thing.txt");
        assertEquals(List.of(), validate());
    }

    @Test
    void rejectsANumberedReference() throws IOException {
        touch("mymod/screenshots/table/1.png");
        assertSingleErrorContaining("1.png");
    }

    @Test
    void rejectsANameThatIsNotALoader() throws IOException {
        writePng("mymod/screenshots/table/b1.7.3-forge.png", solid(2, 2, 0));
        assertSingleErrorContaining("b1.7.3-forge.png");
    }

    @Test
    void rejectsACapitalizedLoaderName() throws IOException {
        writePng("mymod/screenshots/table/B1.7.3-ornithe.png", solid(2, 2, 0));
        assertSingleErrorContaining("B1.7.3-ornithe.png");
    }

    @Test
    void rejectsAnUppercaseExtension() throws IOException {
        writePng("mymod/screenshots/table/" + LOADER + ".PNG", solid(2, 2, 0));
        assertSingleErrorContaining(LOADER + ".PNG");
    }

    @Test
    void rejectsANameWithoutThePngExtension() throws IOException {
        touch("mymod/screenshots/table/" + LOADER);
        assertSingleErrorContaining(LOADER);
    }

    @Test
    void rejectsANonPngFile() throws IOException {
        writePng("mymod/screenshots/table/" + LOADER + ".png", solid(2, 2, 0));
        touch("mymod/screenshots/table/README.md");
        assertSingleErrorContaining("README.md");
    }

    @Test
    void rejectsMixingReferencesWithASubdirectory() throws IOException {
        writePng("mymod/screenshots/table/" + LOADER + ".png", solid(2, 2, 0));
        writePng("mymod/screenshots/table/sub/" + OTHER_LOADER + ".png", solid(2, 2, 0));
        List<String> errors = validate();
        assertTrue(errors.stream().anyMatch(error -> error.contains("mixes")), errors.toString());
    }

    @Test
    void rejectsTwoPixelIdenticalReferences() throws IOException {
        writePng("mymod/screenshots/table/" + LOADER + ".png", solid(2, 2, 0x102030));
        writePng("mymod/screenshots/table/" + OTHER_LOADER + ".png", solid(2, 2, 0x102030));
        assertSingleErrorContaining(OTHER_LOADER + ".png and " + LOADER + ".png");
    }

    @Test
    void acceptsTwoReferencesThatDiffer() throws IOException {
        writePng("mymod/screenshots/table/" + LOADER + ".png", solid(2, 2, 0x102030));
        writePng("mymod/screenshots/table/" + OTHER_LOADER + ".png", solid(2, 2, 0x102031));
        assertEquals(List.of(), validate());
    }

    @Test
    void acceptsReferencesOfDifferentSizes() throws IOException {
        writePng("mymod/screenshots/table/" + LOADER + ".png", solid(2, 2, 0x102030));
        // A different width, and a different height at the same width, so both halves of the size
        // check decide for some pair rather than the width short-circuiting every time.
        writePng("mymod/screenshots/table/" + OTHER_LOADER + ".png", solid(3, 2, 0x102030));
        writePng("mymod/screenshots/table/a1.1.2_01-ornithe.png", solid(2, 3, 0x102030));
        assertEquals(List.of(), validate());
    }

    @Test
    void reportsEveryIdenticalPair() throws IOException {
        // Sorted by name, so the first file is 1.2.5-forge.png and it is the one every later
        // identical file is reported against.
        writePng("mymod/screenshots/table/a1.1.2_01-ornithe.png", solid(2, 2, 0x102030));
        writePng("mymod/screenshots/table/b1.7.3-ornithe.png", solid(2, 2, 0x102030));
        writePng("mymod/screenshots/table/1.2.5-forge.png", solid(2, 2, 0x102030));
        List<String> errors = validate();
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(
                errors.get(0).contains("1.2.5-forge.png and a1.1.2_01-ornithe.png"), errors.get(0));
        assertTrue(errors.get(1).contains("1.2.5-forge.png and b1.7.3-ornithe.png"), errors.get(1));
    }

    @Test
    void rejectsAReferenceThatCannotBeRead() throws IOException {
        touch("mymod/screenshots/table/" + LOADER + ".png");
        assertSingleErrorContaining("could not be read");
    }

    @Test
    void reportsEveryViolationAcrossMods() throws IOException {
        writePng("one/screenshots/table/0.png", solid(2, 2, 0));
        writePng("two/screenshots/chest/" + LOADER + ".png", solid(2, 2, 0));
        writePng("two/screenshots/chest/" + OTHER_LOADER + ".png", solid(2, 2, 0));
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

    @Test
    void twoCapturesOfAnUnchangedFrameDifferInNothing() {
        BufferedImage before = solid(10, 10, 0x203040);

        assertEquals(0.0, GrugScreenshots.changedPercent(before, solid(10, 10, 0x203040)));
    }

    @Test
    void aPixelCountsAsChangedWhenBlueMovesFarEnough() {
        BufferedImage before = solid(4, 1, 0x000000);
        BufferedImage after = solid(4, 1, 0x000000);
        // Only the blue channel, and only on one of the four pixels, and by enough to clear the
        // floor a change of lighting cannot reach.
        after.setRGB(2, 0, 0x00000F);

        assertEquals(25.0, GrugScreenshots.changedPercent(before, after));
    }

    @Test
    void aPixelCountsAsChangedWhenRedMovesFarEnough() {
        BufferedImage before = solid(4, 1, 0x000000);
        BufferedImage after = solid(4, 1, 0x0F0000);

        // The channel comparison is written as three alternatives, so each of the first two has to
        // be
        // the one that decides, not only the last: a check that short-circuits is only as covered
        // as
        // its longest arm.
        assertEquals(100.0, GrugScreenshots.changedPercent(before, after));
    }

    @Test
    void aPixelCountsAsChangedWhenGreenMovesFarEnough() {
        BufferedImage before = solid(4, 1, 0x000000);
        BufferedImage after = solid(4, 1, 0x00FF00);

        assertEquals(100.0, GrugScreenshots.changedPercent(before, after));
    }

    @Test
    void aPixelTheLightingMovedByOnePartIsNotAChange() {
        BufferedImage before = solid(4, 1, 0x000000);
        BufferedImage after = solid(4, 1, 0x000000);
        // One part in 255 on every pixel, which is what per-vertex lighting does to a face between
        // two
        // captures. A comparison that counted this would report a change where there was none, and
        // a
        // test would have to ask for a threshold high enough to hide it, which is the same as
        // asking
        // for the wrong thing more precisely.
        for (int x = 0; x < 4; x++) {
            after.setRGB(x, 0, 0x000001);
        }

        assertEquals(0.0, GrugScreenshots.changedPercent(before, after));
    }

    @Test
    void aWhollyRepaintedFrameDiffersInEveryPixel() {
        assertEquals(
                100.0,
                GrugScreenshots.changedPercent(solid(8, 8, 0x000000), solid(8, 8, 0xFFFFFF)));
    }

    @Test
    void alphaAloneIsNotAChange() {
        BufferedImage before = solid(4, 4, 0x102030);
        BufferedImage after = solid(4, 4, 0x102030);
        after.setRGB(1, 1, 0xFF102030 | 0x40000000);

        // The framebuffer may not even carry alpha, and nothing in the game draws with it, so
        // counting it would make an opaque capture look changed when nothing about the picture was.
        assertEquals(0.0, GrugScreenshots.changedPercent(before, after));
    }

    @Test
    void twoCapturesOfDifferentRectanglesAreRejected() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> GrugScreenshots.changedPercent(solid(4, 4, 0), solid(4, 5, 0)));

        assertTrue(error.getMessage().contains("not two views of the same rectangle"));
    }

    @Test
    void aRectangleOfADifferentWidthIsRejectedToo() {
        // The same size check reached through its other half. Without this the width comparison is
        // only ever exercised as the one that decides, so the height one is never the deciding half
        // and a mismatch in width alone would go untested.
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugScreenshots.changedPercent(solid(4, 4, 0), solid(8, 4, 0)));
    }
}
