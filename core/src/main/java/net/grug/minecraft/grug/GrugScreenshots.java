package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * Reference-image handling shared by every loader's {@code Screenshot.equals()}.
 *
 * <p>The reference path names a directory of numbered PNGs ({@code 1.png}, {@code 2.png}, ...). By
 * default the assertion passes when the capture is pixel-identical to any one of them, and a small
 * per-pixel tolerance can allow each individual pixel to differ slightly where the rendering cannot
 * be reproduced exactly. There is deliberately no single canonical image: the same UI renders
 * differently on each Minecraft version (fonts, item sprites, GUI scaling), so every accepted
 * appearance gets its own file and one test stays green on all of them. It also means a contributor
 * can add their own environment's rendering with a one-file pull request.
 *
 * <p>A directory with no references yet is bootstrapped locally, and for a reference run: the first
 * capture is written as {@code 1.png} and accepted, so a new screenshot test doesn't need its
 * directory created by hand. A normal CI run refuses to bootstrap, so an uncommitted golden fails
 * the run by name instead of being certified by the very capture it should verify. A capture that
 * matches none of an existing directory's references is instead written to the screenshot-artifacts
 * directory (mirroring the reference path) so CI can upload it, and the test fails; promoting that
 * artifact into the reference directory is how a new rendering is accepted.
 */
public final class GrugScreenshots {
    /** Screenshot tests are pixel-exact against references captured at this resolution. */
    public static final int WIDTH = 1280;

    public static final int HEIGHT = 720;

    /**
     * A leaf entry: a lowercase .png whose name is a positive number (1, 2, ...), capped at nine
     * digits. The cap keeps the number inside an int; without it a name like 9999999999.png would
     * match and then throw out of the validator instead of being reported.
     */
    private static final Pattern REFERENCE_NAME = Pattern.compile("[1-9][0-9]{0,8}\\.png");

    /** Where a capture that matched no reference is stashed for CI to upload. */
    private static final String ARTIFACTS_DIRECTORY = "grug-screenshot-artifacts";

    /** ImageMagick compare's default highlight color (#f1001e) at its default alpha (0xCC). */
    private static final int DIFF_HIGHLIGHT = 0xF1001E;

    /** ImageMagick compare's default lowlight color (white) at its default alpha (0xCC). */
    private static final int DIFF_LOWLIGHT = 0xFFFFFF;

    private static final int DIFF_ALPHA = 0xCC;

    private GrugScreenshots() {}

    /**
     * Passes if {@code capture} matches one of the numbered PNGs in {@code referenceDirectory},
     * bootstraps that directory with the capture when it has no references yet, and otherwise
     * reports the closest miss and writes the capture to the artifacts directory.
     *
     * <p>With a {@code tolerancePercent} of 0 the capture must be pixel-identical to a reference;
     * otherwise it passes when no single pixel changed by more than {@code 255 * percent / 100} on
     * any channel. A pass whose least accepted reference needs less than {@code tolerancePercent}
     * fails instead, so the stated tolerance is always the smallest integer that actually passes.
     */
    public static void verify(
            BufferedImage capture,
            File referenceDirectory,
            String referencePath,
            double tolerancePercent) {
        verify(
                capture,
                referenceDirectory,
                referencePath,
                new File(GrugCore.getAdapter().getGameDirectory(), ARTIFACTS_DIRECTORY),
                tolerancePercent);
    }

    /** The real entry point, with the artifacts directory passed in so tests can drive it. */
    static void verify(
            BufferedImage capture,
            File referenceDirectory,
            String referencePath,
            File artifactsRoot,
            double tolerancePercent) {
        List<File> references = listReferences(referenceDirectory);

        // No accepted rendering yet, so this is a brand-new screenshot test or the first local run
        // of an existing one. A committed directory always has at least one PNG, since git cannot
        // store an empty one, so on a normal CI run an empty directory means the golden was never
        // committed: fail and name it instead of certifying the run's own capture. A reference run
        // may still capture its first reference, and a local author may still bootstrap.
        if (references.isEmpty()) {
            if (GrugReference.isCiRun() && !GrugReference.isReferenceRun()) {
                throw Grug.fatal(
                        "Screenshot.equals: no committed reference image for "
                                + referencePath
                                + " in "
                                + referenceDirectory
                                + "; a golden must be committed, not captured during the run.");
            }
            addReference(capture, referenceDirectory, referencePath);
            return;
        }

        int totalPixels = capture.getWidth() * capture.getHeight();
        int closestDifference = Integer.MAX_VALUE;
        String closestName = null;
        BufferedImage closestImage = null;
        // The smallest tolerance that would accept the capture. The assert passes if any reference
        // matches, so this is the least slack any accepted reference needs.
        int minimalPassingTolerance = Integer.MAX_VALUE;
        for (File reference : references) {
            BufferedImage image = read(reference);
            if (image == null) {
                continue;
            }

            if (image.getWidth() != capture.getWidth()
                    || image.getHeight() != capture.getHeight()) {
                // A reference of the wrong size means the crop rectangle changed; every reference,
                // including this one, is now stale and has to be regenerated.
                throw Grug.fatal(
                        "Screenshot reference "
                                + reference.getName()
                                + " in "
                                + referencePath
                                + " is "
                                + image.getWidth()
                                + "x"
                                + image.getHeight()
                                + " but the capture is "
                                + capture.getWidth()
                                + "x"
                                + capture.getHeight()
                                + ". The crop rectangle and every reference image have to agree.");
            }

            // The match is decided by the single worst pixel, so a small area that changed
            // drastically cannot slip under a tolerance the way a count of differing pixels would.
            int maxChange = maxPixelChange(image, capture);
            if (matches(maxChange, tolerancePercent)) {
                minimalPassingTolerance =
                        Math.min(minimalPassingTolerance, requiredTolerance(maxChange));
            }
            // The closest reference is still chosen by differing-pixel count: it keeps the
            // highlighted area in the diff as small as possible for the human looking at it.
            int differing = countDifferences(image, capture);
            if (differing < closestDifference) {
                closestDifference = differing;
                closestName = reference.getName();
                closestImage = image;
            }
        }

        if (minimalPassingTolerance != Integer.MAX_VALUE) {
            // A tolerance higher than the one that actually passes makes the assertion always
            // accept, which hides regressions and stops being a test. Keep the stated number
            // honest.
            if (minimalPassingTolerance < tolerancePercent) {
                throw Grug.fatal(
                        "Screenshot.equals: the capture already passes at tolerance "
                                + minimalPassingTolerance
                                + ", so lower the "
                                + (int) tolerancePercent
                                + " to "
                                + minimalPassingTolerance
                                + ".");
            }
            return;
        }

        // The capture is named after the free slot it would fill, so accepting it is a plain copy
        // of
        // the artifact into the reference directory.
        int targetNumber = nextNumber(referenceDirectory);
        File artifact = writeArtifact(capture, referencePath, artifactsRoot, targetNumber);

        // The closest reference is the useful one to diff against: it keeps the highlighted area as
        // small as possible, so a localized red patch reads as a change while an all-red frame
        // reads
        // as a rendering no reference represents yet. The faded background is the capture either
        // way,
        // since a pixel only counts as matching when the capture and that reference agree there.
        File diff =
                closestImage == null
                        ? null
                        : writeDiff(capture, closestImage, referencePath, artifactsRoot);

        String difference =
                closestName == null
                        ? "none of them could be read"
                        : "the closest ("
                                + closestName
                                + ") differs in "
                                + closestDifference
                                + " of "
                                + totalPixels
                                + " pixels";

        throw Grug.fatal(
                "Screenshot mismatch against "
                        + referencePath
                        + ": the capture matches none of the "
                        + references.size()
                        + " reference image(s): "
                        + difference
                        + ". The capture was written to "
                        + artifact
                        + (diff == null ? "." : " and a diff to " + diff + ".")
                        + " To accept it, copy the capture into "
                        + referenceDirectory
                        + " as "
                        + targetNumber
                        + ".png.");
    }

    /**
     * Checks the reference trees under a mods directory against the {@code screenshots/}
     * convention, returning every violation (empty when the tree is valid). The convention is that,
     * under a mod's {@code screenshots/}, every directory is either a group (subdirectories only)
     * or a reference (only {@code 1.png}, {@code 2.png}, ... with no gaps), the two are never
     * mixed, and a name is exactly a lowercase {@code .png} of a positive number.
     *
     * <p>This is deliberately the only implementation of the rules: the loaders run it before a
     * test run so authors see violations locally, and CI fails through the very same path, so the
     * two can't drift apart.
     */
    public static List<String> validateReferenceTrees(File modsDirectory) {
        List<String> errors = new ArrayList<>();
        File[] modDirectories = modsDirectory.listFiles(File::isDirectory);
        if (modDirectories == null) {
            return errors;
        }
        for (File modDirectory : modDirectories) {
            File screenshots = new File(modDirectory, "screenshots");
            if (screenshots.isDirectory()) {
                validateReferenceDirectory(
                        screenshots, modDirectory.getName() + "/screenshots", errors);
            }
        }
        return errors;
    }

    private static void validateReferenceDirectory(
            File directory, String path, List<String> errors) {
        File[] entries = listEntries(directory);

        List<File> subdirectories = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File entry : entries) {
            (entry.isDirectory() ? subdirectories : files).add(entry);
        }

        if (!subdirectories.isEmpty() && !files.isEmpty()) {
            errors.add(
                    path
                            + " mixes reference PNGs with subdirectories; a directory is either a"
                            + " group of subdirectories or a directory of numbered PNGs, never"
                            + " both.");
        }

        if (!files.isEmpty()) {
            Set<Integer> numbers = new TreeSet<>();
            for (File file : files) {
                if (!REFERENCE_NAME.matcher(file.getName()).matches()) {
                    errors.add(
                            path
                                    + "/"
                                    + file.getName()
                                    + " is not a valid reference name; references are numbered from"
                                    + " 1 (1.png, 2.png, ...) with the exact lowercase .png"
                                    + " extension.");
                    continue;
                }
                numbers.add(referenceNumber(file));
            }

            int expected = 1;
            for (int number : numbers) {
                if (number != expected) {
                    errors.add(
                            path
                                    + " is missing "
                                    + expected
                                    + ".png, so its references are not"
                                    + " numbered 1, 2, 3, ... without gaps.");
                    // Report the gap once, rather than cascading it into every later number.
                    break;
                }
                expected++;
            }
        }

        for (File subdirectory : subdirectories) {
            validateReferenceDirectory(subdirectory, path + "/" + subdirectory.getName(), errors);
        }
    }

    /** The numbered references in a directory, ordered 1, 2, 3, ... If it doesn't exist, empty. */
    private static List<File> listReferences(File directory) {
        List<File> references = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) {
            return references;
        }
        for (File file : files) {
            if (REFERENCE_NAME.matcher(file.getName()).matches()) {
                references.add(file);
            }
        }
        references.sort(Comparator.comparingInt(GrugScreenshots::referenceNumber));
        return references;
    }

    private static int referenceNumber(File file) {
        return Integer.parseInt(file.getName().substring(0, file.getName().length() - 4));
    }

    /**
     * Writes the capture as the directory's first reference, creating the directory if needed. Only
     * called when the directory has no references yet, so this always writes {@code 1.png}.
     */
    private static void addReference(BufferedImage capture, File directory, String referencePath) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw Grug.fatal(
                    "Screenshot: could not create the reference directory "
                            + referencePath
                            + " ("
                            + directory
                            + ").");
        }
        File reference = writeFirstReference(capture, directory, referencePath);
        String message =
                "Wrote the first screenshot reference "
                        + referencePath
                        + "/"
                        + reference.getName()
                        + " ("
                        + capture.getWidth()
                        + "x"
                        + capture.getHeight()
                        + "); re-run to verify it.";
        System.out.println("[GRUG CI] " + message);
        synchronized (Grug.printQueue) {
            Grug.printQueue.add(message);
        }
    }

    @GrugGenerated("reference write: a failed write is reported, not measured")
    private static File writeFirstReference(
            BufferedImage capture, File directory, String referencePath) {
        try {
            File reference = new File(directory, nextNumber(directory) + ".png");
            ImageIO.write(capture, "png", reference);
            return reference;
        } catch (Exception e) {
            throw Grug.fatal(
                    "Screenshot: failed to write a reference for " + referencePath + ": " + e);
        }
    }

    /**
     * Writes the unmatched capture to the artifacts directory under the given number, which is the
     * free slot in the reference directory, so promoting it is a plain copy.
     */
    private static File writeArtifact(
            BufferedImage capture, String referencePath, File artifactsRoot, int number) {
        File directory = artifactDirectory(referencePath, artifactsRoot);
        if (!directory.exists() && !directory.mkdirs()) {
            return new File(directory, number + ".png");
        }
        return writeArtifactFile(capture, directory, referencePath, number);
    }

    @GrugGenerated("artifact write: never hide the actual screenshot mismatch")
    private static File writeArtifactFile(
            BufferedImage capture, File directory, String referencePath, int number) {
        try {
            File artifact = new File(directory, number + ".png");
            ImageIO.write(capture, "png", artifact);
            return artifact;
        } catch (Exception e) {
            // The artifact is a convenience for collecting missing references; never let failing to
            // write it hide the actual screenshot mismatch.
            return new File(referencePath, number + ".png");
        }
    }

    /** Where a capture that matched no reference, and its diff, is stashed for CI to upload. */
    private static File artifactDirectory(String referencePath, File artifactsRoot) {
        return new File(artifactsRoot, referencePath);
    }

    /**
     * Writes a visual diff of the capture against {@code reference}: differing pixels in
     * ImageMagick compare's default highlight color and matching pixels faded towards white, so the
     * red stands out. The colors and the 0xCC alpha are exactly ImageMagick's defaults ({@code
     * #f1001ecc} highlight, {@code #ffffffcc} lowlight) composited over the capture, so this is
     * pixel-identical to {@code magick compare capture reference diff}.
     */
    private static File writeDiff(
            BufferedImage capture,
            BufferedImage reference,
            String referencePath,
            File artifactsRoot) {
        int width = capture.getWidth();
        int height = capture.getHeight();
        BufferedImage diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int actual = capture.getRGB(x, y);
                int expected = reference.getRGB(x, y);
                // Alpha is not part of the comparison; only the RGB channels matter.
                boolean matches = (actual & 0xFFFFFF) == (expected & 0xFFFFFF);
                diff.setRGB(x, y, blend(matches ? DIFF_LOWLIGHT : DIFF_HIGHLIGHT, actual));
            }
        }

        File directory = artifactDirectory(referencePath, artifactsRoot);
        if (!ensureDirectory(directory)) {
            return new File(directory, "diff.png");
        }
        return writeDiffFile(diff, directory, referencePath);
    }

    /** Creates the directory if it is missing, reporting whether it is usable afterwards. */
    @GrugGenerated("artifact directory creation")
    private static boolean ensureDirectory(File directory) {
        return directory.exists() || directory.mkdirs();
    }

    @GrugGenerated("diff write: never hide the actual screenshot mismatch")
    private static File writeDiffFile(BufferedImage diff, File directory, String referencePath) {
        try {
            File file = new File(directory, "diff.png");
            ImageIO.write(diff, "png", file);
            return file;
        } catch (Exception e) {
            return new File(referencePath, "diff.png");
        }
    }

    /**
     * Composites a highlight/lowlight color over a capture pixel using ImageMagick's 0xCC alpha.
     */
    static int blend(int foreground, int background) {
        int inverse = 255 - DIFF_ALPHA;
        int r =
                (((foreground >> 16) & 0xFF) * DIFF_ALPHA
                                + ((background >> 16) & 0xFF) * inverse
                                + 127)
                        / 255;
        int g =
                (((foreground >> 8) & 0xFF) * DIFF_ALPHA
                                + ((background >> 8) & 0xFF) * inverse
                                + 127)
                        / 255;
        int b = ((foreground & 0xFF) * DIFF_ALPHA + (background & 0xFF) * inverse + 127) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /** The smallest number at or above 1 not already used, so gaps get filled rather than left. */
    private static int nextNumber(File directory) {
        Set<Integer> used = new HashSet<>();
        for (File file : listEntries(directory)) {
            if (REFERENCE_NAME.matcher(file.getName()).matches()) {
                used.add(referenceNumber(file));
            }
        }
        int next = 1;
        while (used.contains(next)) {
            next++;
        }
        return next;
    }

    /** A directory's entries, or an empty array when it cannot be listed. */
    @GrugGenerated("defensive: a directory that cannot be listed")
    private static File[] listEntries(File directory) {
        File[] entries = directory.listFiles();
        return entries == null ? new File[0] : entries;
    }

    private static BufferedImage read(File file) {
        try {
            return ImageIO.read(file);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a pixel change of {@code maxChange} (0 to 255) is accepted at the given tolerance:
     * the change may be at most {@code 255 * percent / 100} on every channel. A change of 0 always
     * passes, so percent 0 stays pixel-exact.
     */
    private static boolean matches(int maxChange, double tolerancePercent) {
        if (maxChange == 0) return true;
        return tolerancePercent > 0 && 100.0 * maxChange <= 255.0 * tolerancePercent;
    }

    /**
     * The smallest integer tolerance that would accept a maximum pixel change of {@code maxChange}:
     * {@code ceil(100 * maxChange / 255)}, or 0 when nothing changed. Integer arithmetic keeps the
     * ceiling exact.
     */
    private static int requiredTolerance(int maxChange) {
        if (maxChange == 0) return 0;
        return (100 * maxChange + 254) / 255;
    }

    /**
     * The largest change any single pixel underwent, as the maximum absolute per-channel difference
     * between the two images (0 to 255). Alpha is not compared: only the RGB channels matter.
     */
    private static int maxPixelChange(BufferedImage a, BufferedImage b) {
        int maxChange = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int actual = a.getRGB(x, y);
                int expected = b.getRGB(x, y);
                int change =
                        Math.max(
                                Math.abs(((actual >> 16) & 0xFF) - ((expected >> 16) & 0xFF)),
                                Math.max(
                                        Math.abs(((actual >> 8) & 0xFF) - ((expected >> 8) & 0xFF)),
                                        Math.abs((actual & 0xFF) - (expected & 0xFF))));
                if (change > maxChange) {
                    maxChange = change;
                }
            }
        }
        return maxChange;
    }

    private static int countDifferences(BufferedImage a, BufferedImage b) {
        int differing = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        return differing;
    }
}
