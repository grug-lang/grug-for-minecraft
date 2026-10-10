package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Covers {@link GrugScreenshots#verify}: the any-reference match, a match at the smallest tolerance
 * that passes, a miss below it, a tolerance higher than the capture needs, bootstrapping a
 * directory that has no references yet (locally and on a reference run), refusing to bootstrap on a
 * normal CI run, accepting a miss under the loader's own name (locally, replacing a previous
 * reference of the same loader) and declining to add one when another loader's reference already
 * matches, the mismatch path (artifact + magick-style diff), and the exact diff colors.
 */
class GrugScreenshotsVerifyTest {

    private static final String REFERENCE_PATH = "mymod/screenshots/table";

    /** The loader this test drives, which is whose name a written reference carries. */
    private static final String LOADER_NAME = "b1.7.3-ornithe";

    /** A different loader, whose reference must never be replaced by this loader's update. */
    private static final String OTHER_LOADER = "1.2.5-forge";

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
        // Pin the branch flags: the surrounding environment may already be a CI or reference run,
        // and these tests exercise the local and reference bootstrap separately, so start from an
        // explicit off.
        System.setProperty(GrugReference.CI_PROPERTY, "0");
        System.setProperty(GrugReference.PROPERTY, "0");
        System.setProperty(GrugReference.UPDATE_GOLDENS_PROPERTY, "0");
    }

    @AfterEach
    void clearTheFlags() {
        System.clearProperty(GrugReference.CI_PROPERTY);
        System.clearProperty(GrugReference.PROPERTY);
        System.clearProperty(GrugReference.UPDATE_GOLDENS_PROPERTY);
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
                capture,
                references.toFile(),
                REFERENCE_PATH,
                artifacts.toFile(),
                LOADER_NAME,
                tolerancePercent);
    }

    @Test
    void passesWhenTheCaptureMatchesAnyReference() throws Exception {
        write(references.resolve(OTHER_LOADER + ".png"), solid(2, 2, RED));
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, GREEN));

        // It skips the other loader's reference and matches this loader's.
        assertDoesNotThrow(() -> verify(solid(2, 2, GREEN)));
        assertFalse(Files.exists(artifacts), "a passing capture leaves no artifacts");
    }

    @Test
    void passesWhenTheToleranceIsExactlyTheSmallestThatPasses() throws Exception {
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, 0));

        // One pixel's red channel changes by 0x33 (51), so the per-pixel minimum is
        // ceil(100 * 51 / 255) = 20 and a 20 tolerance has to pass.
        BufferedImage capture = solid(2, 2, 0);
        capture.setRGB(1, 1, 0x330000);

        assertDoesNotThrow(() -> verify(capture, 20));
        assertFalse(Files.exists(artifacts), "a capture within tolerance leaves no artifacts");
    }

    @Test
    void failsWhenTheCaptureExceedsTolerance() throws Exception {
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, 0));

        // The same 51 needs 20, so a 19 tolerance is a genuine mismatch.
        BufferedImage capture = solid(2, 2, 0);
        capture.setRGB(1, 1, 0x330000);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(capture, 19));
        assertTrue(
                error.getMessage().contains("the closest (" + LOADER_NAME + ".png)"),
                error.getMessage());
    }

    @Test
    void failsWhenTheToleranceIsHigherThanTheCaptureNeeds() throws Exception {
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, 0));

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
        // The first reference changes by 0x66 (102, needs 40%); the second by 0x33 (51, needs 20%).
        // The assert passes if any reference matches, so the smaller value is the one that sets the
        // minimum.
        BufferedImage needsForty = solid(2, 2, 0);
        needsForty.setRGB(1, 1, 0x660000);
        write(references.resolve(OTHER_LOADER + ".png"), needsForty);
        BufferedImage needsTwenty = solid(2, 2, 0);
        needsTwenty.setRGB(1, 1, 0x330000);
        write(references.resolve(LOADER_NAME + ".png"), needsTwenty);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(2, 2, 0), 50));
        assertTrue(
                error.getMessage().contains("already passes at tolerance 20"), error.getMessage());
    }

    @Test
    void bootstrapsTheFirstReferenceIntoAnEmptyDirectory() throws Exception {
        BufferedImage capture = solid(2, 2, GREEN);

        assertDoesNotThrow(() -> verify(capture));
        assertTrue(
                Files.exists(references.resolve(LOADER_NAME + ".png")),
                "the first reference is written under the loader's own name");
        assertEquals(
                capture.getRGB(0, 0), read(references.resolve(LOADER_NAME + ".png")).getRGB(0, 0));
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
                                capture,
                                missing.toFile(),
                                REFERENCE_PATH,
                                artifacts.toFile(),
                                LOADER_NAME,
                                0));
        assertTrue(
                Files.exists(missing.resolve(LOADER_NAME + ".png")),
                "the directory and its first reference are created");
    }

    @Test
    void failsInCiWhenNoReferenceIsCommitted() {
        System.setProperty(GrugReference.CI_PROPERTY, "1");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(2, 2, RED)));

        assertTrue(error.getMessage().contains("no committed reference image"), error.getMessage());
        assertTrue(error.getMessage().contains(REFERENCE_PATH), error.getMessage());
        assertFalse(
                Files.exists(references.resolve(LOADER_NAME + ".png")),
                "a CI run never writes the golden it is meant to verify against");
        assertFalse(Files.exists(artifacts), "the missing golden is reported before anything else");
    }

    @Test
    void bootstrapsInAReferenceRunEvenInCi() throws Exception {
        System.setProperty(GrugReference.CI_PROPERTY, "1");
        System.setProperty(GrugReference.PROPERTY, "1");

        BufferedImage capture = solid(2, 2, GREEN);
        assertDoesNotThrow(() -> verify(capture));

        assertTrue(
                Files.exists(references.resolve(LOADER_NAME + ".png")),
                "a reference run still captures its first reference");
        assertEquals(
                capture.getRGB(0, 0), read(references.resolve(LOADER_NAME + ".png")).getRGB(0, 0));
    }

    @Test
    void acceptsAnUnmatchedCaptureUnderTheLoadersOwnNameWhenUpdatingGoldens() throws Exception {
        System.setProperty(GrugReference.UPDATE_GOLDENS_PROPERTY, "1");
        write(references.resolve(OTHER_LOADER + ".png"), solid(2, 2, RED));

        BufferedImage capture = solid(2, 2, GREEN);
        assertDoesNotThrow(() -> verify(capture));

        assertTrue(
                Files.exists(references.resolve(LOADER_NAME + ".png")),
                "the miss is accepted under the loader's own name");
        assertEquals(
                capture.getRGB(0, 0), read(references.resolve(LOADER_NAME + ".png")).getRGB(0, 0));
        assertFalse(Files.exists(artifacts), "an accepted capture leaves no artifact");
    }

    @Test
    void replacesTheLoadersOwnReferenceWhenUpdatingGoldens() throws Exception {
        System.setProperty(GrugReference.UPDATE_GOLDENS_PROPERTY, "1");
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, RED));

        BufferedImage capture = solid(2, 2, GREEN);
        assertDoesNotThrow(() -> verify(capture));

        // One file per loader: a rendering change replaces that loader's own reference rather than
        // adding a second one, which the naming scheme has no room for.
        assertEquals(
                capture.getRGB(0, 0), read(references.resolve(LOADER_NAME + ".png")).getRGB(0, 0));
        int referencesOnDisk = 0;
        for (File file : references.toFile().listFiles()) {
            if (file.getName().endsWith(".png")) {
                referencesOnDisk++;
            }
        }
        assertEquals(
                1, referencesOnDisk, "the update replaced the reference instead of adding one");
    }

    @Test
    void leavesAMatchAloneWhenUpdatingGoldens() throws Exception {
        System.setProperty(GrugReference.UPDATE_GOLDENS_PROPERTY, "1");
        write(references.resolve(OTHER_LOADER + ".png"), solid(2, 2, GREEN));

        assertDoesNotThrow(() -> verify(solid(2, 2, GREEN)));

        assertFalse(
                Files.exists(references.resolve(LOADER_NAME + ".png")),
                "a capture that another loader's reference already matches adds no reference");
        assertFalse(Files.exists(artifacts), "a matching capture leaves no artifact");
    }

    @Test
    void acceptsTheFirstReferenceInCiWhenUpdatingGoldens() throws Exception {
        System.setProperty(GrugReference.CI_PROPERTY, "1");
        System.setProperty(GrugReference.UPDATE_GOLDENS_PROPERTY, "1");

        BufferedImage capture = solid(2, 2, GREEN);
        assertDoesNotThrow(() -> verify(capture));

        assertTrue(
                Files.exists(references.resolve(LOADER_NAME + ".png")),
                "an explicit update writes the first reference even in CI");
        assertEquals(
                capture.getRGB(0, 0), read(references.resolve(LOADER_NAME + ".png")).getRGB(0, 0));
    }

    @Test
    void failsWhenNoReferenceMatchesAndWritesTheCaptureAndADiff() throws Exception {
        write(references.resolve(OTHER_LOADER + ".png"), solid(2, 2, RED));

        BufferedImage capture = solid(2, 2, RED);
        capture.setRGB(1, 1, BLUE);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(capture));
        assertTrue(
                error.getMessage().contains("matches none of the 1 reference image(s)"),
                error.getMessage());
        assertTrue(
                error.getMessage().contains("the closest (" + OTHER_LOADER + ".png)"),
                error.getMessage());
        assertTrue(error.getMessage().contains("as " + LOADER_NAME + ".png"), error.getMessage());

        // The artifact carries the loader's name, so accepting it is a plain copy.
        Path artifact = artifacts.resolve(REFERENCE_PATH).resolve(LOADER_NAME + ".png");
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
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, RED));

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(3, 3, RED)));
        assertTrue(error.getMessage().contains("have to agree"), error.getMessage());
        assertFalse(
                Files.exists(artifacts),
                "a stale reference is reported before anything is written");
    }

    @Test
    void fallsBackWhenTheArtifactCannotBeWritten() throws Exception {
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, RED));
        // Directories where the artifact and diff files would go, so ImageIO.write fails and the
        // fallback paths run.
        Path artifactDir = artifacts.resolve(REFERENCE_PATH);
        Files.createDirectories(artifactDir.resolve(LOADER_NAME + ".png"));
        Files.createDirectories(artifactDir.resolve("diff.png"));

        BufferedImage capture = solid(2, 2, RED);
        capture.setRGB(1, 1, BLUE);

        assertThrows(IllegalStateException.class, () -> verify(capture));
    }

    @Test
    void treatsAnUnreadableReferenceAsNoMatch() throws Exception {
        Files.writeString(references.resolve(LOADER_NAME + ".png"), "not a png");

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
                                LOADER_NAME,
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
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, RED));

        // Same width, different height: the width check passes and the height check runs.
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(2, 3, RED)));
        assertTrue(error.getMessage().contains("have to agree"), error.getMessage());
    }

    @Test
    void reportsTheClosestOfSeveralReferences() throws Exception {
        write(references.resolve(OTHER_LOADER + ".png"), solid(2, 2, RED));
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, GREEN));

        BufferedImage capture = solid(2, 2, RED);
        capture.setRGB(1, 1, BLUE);

        // The other loader's reference differs in one pixel, this loader's in all four, so the
        // closer one wins.
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(capture));
        assertTrue(
                error.getMessage().contains("the closest (" + OTHER_LOADER + ".png)"),
                error.getMessage());
    }

    @Test
    void fallsBackToTheReferencePathWhenTheArtifactsRootIsAFile() throws Exception {
        write(references.resolve(LOADER_NAME + ".png"), solid(2, 2, RED));

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
                                LOADER_NAME,
                                0));
    }

    @Test
    void treatsADirectoryNamedLikeAReferenceAsUnreadable() throws Exception {
        // listReferences sorts a directory named like a reference as a reference; reading it
        // throws, which the read seam turns into "couldn't be read". notes.txt exercises the
        // non-reference name.
        Files.createDirectories(references.resolve(LOADER_NAME + ".png"));
        Files.writeString(references.resolve("notes.txt"), "not a reference");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> verify(solid(2, 2, RED)));
        assertTrue(error.getMessage().contains("none of them could be read"), error.getMessage());
    }
}
