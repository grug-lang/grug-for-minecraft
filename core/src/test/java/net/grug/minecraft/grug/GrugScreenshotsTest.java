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

/**
 * Covers the {@code screenshots/} tree convention enforced by {@link
 * GrugScreenshots#validateReferenceTrees(java.io.File)}, and the share-of-pixels-changed measure
 * {@link GrugScreenshots#changedPercent} reports for a test that compares two of its own captures.
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
    void twoCapturesOfAnUnchangedFrameDifferInNothing() {
        BufferedImage before = solid(10, 10, 0x203040);

        assertEquals(0.0, GrugScreenshots.changedPercent(before, solid(10, 10, 0x203040)));
    }

    @Test
    void aPixelCountsAsChangedWhenAChannelMovesFarEnough() {
        BufferedImage before = solid(4, 1, 0x000000);
        BufferedImage after = solid(4, 1, 0x000000);
        // Only the blue channel, and only on one of the four pixels, and by enough to clear the
        // floor a change of lighting cannot reach.
        after.setRGB(2, 0, 0x00000F);

        assertEquals(25.0, GrugScreenshots.changedPercent(before, after));
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
