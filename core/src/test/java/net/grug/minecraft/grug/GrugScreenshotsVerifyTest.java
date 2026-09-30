package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Covers {@link GrugScreenshots#verify}: the any-reference match, a match at the smallest tolerance
 * that passes, a miss below it, a tolerance higher than the capture needs, bootstrapping a
 * directory that has no references yet, the mismatch path (artifact + magick-style diff), and the
 * exact diff colors.
 */
class GrugScreenshotsVerifyTest {

    private static final String REFERENCE_PATH = "mymod/screenshots/table";
    private static final int RED = 0x0000FF;
    private static final int GREEN = 0x00FF00;
    private static final int BLUE = 0xFF0000;

    @TempDir Path temp;

    private Path references;
    private Path artifacts;

    @BeforeEach
    void setUp() throws Exception {
        references = temp.resolve("references");
        artifacts = temp.resolve("artifacts");
        Files.createDirectories(references);
    }

    private static BufferedImage solid(int width, int height, int rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static void write(Path path, BufferedImage image) throws Exception {
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    private static BufferedImage read(Path path) throws Exception {
        return ImageIO.read(path.toFile());
    }

    /** Drives the package-private seam with a temp artifacts directory and an exact comparison. */
    private void verify(BufferedImage capture) {
        verify(capture, 0);
    }

    /** Drives the package-private seam with a tolerance percent. */
    private void verify(BufferedImage capture, double tolerancePercent) {
        GrugScreenshots.verify(
                capture, references.toFile(), REFERENCE_PATH, artifacts.toFile(), tolerancePercent);
    }

    @Test
    void passesWhenTheCaptureMatchesAnyReference() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, RED));
        write(references.resolve("2.png"), solid(2, 2, GREEN));

        // It skips 1.png and matches 2.png.
        assertDoesNotThrow(() -> verify(solid(2, 2, GREEN)));
        assertFalse(Files.exists(artifacts), "a passing capture leaves no artifacts");
    }

    @Test
    void passesWhenTheToleranceIsExactlyTheSmallestThatPasses() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, 0));

        // One pixel's red channel changes by 0x33 (51), so the per-pixel minimum is
        // ceil(100 * 51 / 255) = 20 and a 20 tolerance has to pass.
        BufferedImage capture = solid(2, 2, 0);
        capture.setRGB(1, 1, 0x330000);

        assertDoesNotThrow(() -> verify(capture, 20));
        assertFalse(Files.exists(artifacts), "a capture within tolerance leaves no artifacts");
    }

    @Test
    void failsWhenTheCaptureExceedsTolerance() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, 0));

        // The same 51 needs 20, so a 19 tolerance is a genuine mismatch.
        BufferedImage capture = solid(2, 2, 0);
        capture.setRGB(1, 1, 0x330000);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(capture, 19));
        assertTrue(error.getMessage().contains("the closest (1.png)"), error.getMessage());
    }

    @Test
    void failsWhenTheToleranceIsHigherThanTheCaptureNeeds() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, 0));

        BufferedImage capture = solid(2, 2, 0);
        capture.setRGB(1, 1, 0x330000);

        // 20 is enough, so 21 is dishonest and has to be reported with the smaller value.
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(capture, 21));
        assertTrue(
                error.getMessage()
                        .contains(
                                "Screenshot.equals: the capture already passes at tolerance 20, so"
                                        + " lower the 21 to 20."),
                error.getMessage());
    }

    @Test
    void reportsTheSmallestToleranceAcrossReferences() throws Exception {
        // 1.png changes by 0x66 (102, needs 40%); 2.png changes by 0x33 (51, needs 20%). The assert
        // passes if any reference matches, so the smaller value is the one that sets the minimum.
        BufferedImage needsForty = solid(2, 2, 0);
        needsForty.setRGB(1, 1, 0x660000);
        write(references.resolve("1.png"), needsForty);
        BufferedImage needsTwenty = solid(2, 2, 0);
        needsTwenty.setRGB(1, 1, 0x330000);
        write(references.resolve("2.png"), needsTwenty);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(2, 2, 0), 50));
        assertTrue(
                error.getMessage().contains("already passes at tolerance 20"), error.getMessage());
    }

    @Test
    void bootstrapsTheFirstReferenceIntoAnEmptyDirectory() throws Exception {
        BufferedImage capture = solid(2, 2, GREEN);

        assertDoesNotThrow(() -> verify(capture));
        assertTrue(Files.exists(references.resolve("1.png")), "the first reference is written");
        assertEquals(capture.getRGB(0, 0), read(references.resolve("1.png")).getRGB(0, 0));
        assertFalse(Files.exists(artifacts), "bootstrapping doesn't write an artifact");

        // A re-run now compares against the reference it just bootstrapped.
        assertDoesNotThrow(() -> verify(capture));
    }

    @Test
    void bootstrapsWhenTheReferenceDirectoryDoesNotExist() throws Exception {
        Path missing = temp.resolve("missing");
        BufferedImage capture = solid(2, 2, RED);

        assertDoesNotThrow(
                () ->
                        GrugScreenshots.verify(
                                capture, missing.toFile(), REFERENCE_PATH, artifacts.toFile(), 0));
        assertTrue(
                Files.exists(missing.resolve("1.png")),
                "the directory and its first reference are created");
    }

    @Test
    void failsWhenNoReferenceMatchesAndWritesTheCaptureAndADiff() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, RED));

        BufferedImage capture = solid(2, 2, RED);
        capture.setRGB(1, 1, BLUE);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(capture));
        assertTrue(
                error.getMessage().contains("matches none of the 1 reference image(s)"),
                error.getMessage());
        assertTrue(error.getMessage().contains("the closest (1.png)"), error.getMessage());
        assertTrue(error.getMessage().contains("as 2.png"), error.getMessage());

        // The artifact is named after the free slot, so accepting it is a plain copy.
        Path artifact = artifacts.resolve(REFERENCE_PATH).resolve("2.png");
        assertTrue(Files.exists(artifact), "the unmatched capture is written for CI to collect");
        assertEquals(capture.getRGB(0, 0), read(artifact).getRGB(0, 0));
        assertEquals(capture.getRGB(1, 1), read(artifact).getRGB(1, 1));

        Path diffPath = artifacts.resolve(REFERENCE_PATH).resolve("diff.png");
        assertTrue(Files.exists(diffPath), "a diff is written next to the artifact");
        BufferedImage diff = read(diffPath);
        // Matching pixels are the capture faded towards white; the one differing pixel is the red
        // highlight, both composited over the capture at ImageMagick's 0xCC alpha.
        assertEquals(GrugScreenshots.blend(0xFFFFFF, RED), diff.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(GrugScreenshots.blend(0xFFFFFF, RED), diff.getRGB(1, 0) & 0xFFFFFF);
        assertEquals(GrugScreenshots.blend(0xFFFFFF, RED), diff.getRGB(0, 1) & 0xFFFFFF);
        assertEquals(GrugScreenshots.blend(0xF1001E, BLUE), diff.getRGB(1, 1) & 0xFFFFFF);
    }

    @Test
    void failsWhenAReferenceHasADifferentSize() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, RED));

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(3, 3, RED)));
        assertTrue(error.getMessage().contains("have to agree"), error.getMessage());
        assertFalse(
                Files.exists(artifacts),
                "a stale reference is reported before anything is written");
    }

    @Test
    void fallsBackWhenTheArtifactCannotBeWritten() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, RED));
        // Directories where the artifact and diff files would go, so ImageIO.write fails and the
        // fallback paths run.
        Path artifactDir = artifacts.resolve(REFERENCE_PATH);
        Files.createDirectories(artifactDir.resolve("2.png"));
        Files.createDirectories(artifactDir.resolve("diff.png"));

        BufferedImage capture = solid(2, 2, RED);
        capture.setRGB(1, 1, BLUE);

        assertThrows(IllegalStateException.class, () -> verify(capture));
    }

    @Test
    void treatsAnUnreadableReferenceAsNoMatch() throws Exception {
        Files.writeString(references.resolve("1.png"), "not a png");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(2, 2, RED)));
        assertTrue(error.getMessage().contains("none of them could be read"), error.getMessage());
    }

    @Test
    void failsWhenTheReferenceDirectoryCannotBeCreated() throws Exception {
        Path blocked = temp.resolve("blocked");
        Files.writeString(blocked, "a file, not a directory");

        assertThrows(
                IllegalStateException.class,
                () ->
                        GrugScreenshots.verify(
                                solid(2, 2, RED),
                                blocked.resolve("child").toFile(),
                                REFERENCE_PATH,
                                artifacts.toFile(),
                                0));
    }

    @Test
    void blendMatchesImageMagickDefaults() {
        // #ffffffcc and #f1001ecc composited over srgb(58,95,138): the exact colors `magick
        // compare`
        // emits (verified byte-for-byte against it on a real capture/reference pair).
        assertEquals(0xD8DFE8, GrugScreenshots.blend(0xFFFFFF, 0x3A5F8A));
        assertEquals(0xCC1334, GrugScreenshots.blend(0xF1001E, 0x3A5F8A));
    }

    @Test
    void failsWhenAReferenceHasADifferentHeight() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, RED));

        // Same width, different height: the width check passes and the height check runs.
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(2, 3, RED)));
        assertTrue(error.getMessage().contains("have to agree"), error.getMessage());
    }

    @Test
    void reportsTheClosestOfSeveralReferences() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, RED));
        write(references.resolve("2.png"), solid(2, 2, GREEN));

        BufferedImage capture = solid(2, 2, RED);
        capture.setRGB(1, 1, BLUE);

        // 1.png differs in one pixel, 2.png in all four, so the closer one wins.
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(capture));
        assertTrue(error.getMessage().contains("the closest (1.png)"), error.getMessage());
    }

    @Test
    void fallsBackToTheReferencePathWhenTheArtifactsRootIsAFile() throws Exception {
        write(references.resolve("1.png"), solid(2, 2, RED));

        Path artifactsFile = temp.resolve("artifacts-is-a-file");
        Files.writeString(artifactsFile, "not a directory");

        BufferedImage capture = solid(2, 2, RED);
        capture.setRGB(1, 1, BLUE);

        assertThrows(
                IllegalStateException.class,
                () ->
                        GrugScreenshots.verify(
                                capture,
                                references.toFile(),
                                REFERENCE_PATH,
                                artifactsFile.toFile(),
                                0));
    }

    @Test
    void treatsADirectoryNamedLikeAReferenceAsUnreadable() throws Exception {
        // listReferences sorts a directory named 1.png as a reference; reading it throws, which the
        // read seam turns into "couldn't be read". notes.txt exercises the non-reference name.
        Files.createDirectories(references.resolve("1.png"));
        Files.writeString(references.resolve("notes.txt"), "not a reference");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(2, 2, RED)));
        assertTrue(error.getMessage().contains("none of them could be read"), error.getMessage());
    }
}
