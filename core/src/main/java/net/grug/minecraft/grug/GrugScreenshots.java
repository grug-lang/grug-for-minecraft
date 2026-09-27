package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference-image handling shared by every loader's {@code Test.assert_screenshot_equals()}.
 *
 * <p>The reference path names a directory of numbered PNGs ({@code 1.png}, {@code 2.png}, ...). The
 * assertion passes when the capture is pixel-identical to any one of them. There is deliberately no
 * single canonical image: the same UI renders differently on each Minecraft version (fonts, item
 * sprites, GUI scaling), so every accepted appearance gets its own file and one test stays green on
 * all of them. It also means a contributor can add their own environment's rendering with a one-file
 * pull request.
 *
 * <p>In {@code GRUG_UPDATE_SCREENSHOTS} mode a capture that matches nothing is appended as the next
 * free number, so re-running is idempotent and an existing reference is never overwritten. A capture
 * that matches nothing in a normal run is written to the screenshot-artifacts directory (mirroring
 * the reference path) so CI can upload it, which is how missing references get collected.
 */
public final class GrugScreenshots {
    /** Screenshot tests are pixel-exact against references captured at this resolution. */
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;

    /** A leaf entry: a lowercase .png whose name is a positive number (1, 2, ...). */
    private static final Pattern REFERENCE_NAME = Pattern.compile("[1-9][0-9]*\\.png");

    /** Where a capture that matched no reference is stashed for CI to upload. */
    private static final String ARTIFACTS_DIRECTORY = "grug-screenshot-artifacts";

    /** ImageMagick compare's default highlight color (#f1001e) at its default alpha (0xCC). */
    private static final int DIFF_HIGHLIGHT = 0xF1001E;

    /** ImageMagick compare's default lowlight color (white) at its default alpha (0xCC). */
    private static final int DIFF_LOWLIGHT = 0xFFFFFF;

    private static final int DIFF_ALPHA = 0xCC;

    private GrugScreenshots() {
    }

    /**
     * Passes if {@code capture} equals one of the numbered PNGs in {@code referenceDirectory}, adds
     * it as a new reference in update mode, and otherwise reports the closest miss and writes the
     * capture to the artifacts directory.
     */
    public static void verify(BufferedImage capture, File referenceDirectory, String referencePath) {
        List<File> references = listReferences(referenceDirectory);

        int closestDifference = Integer.MAX_VALUE;
        String closestName = null;
        BufferedImage closestImage = null;
        for (File reference : references) {
            BufferedImage image = read(reference);
            if (image == null) {
                continue;
            }

            if (image.getWidth() != capture.getWidth() || image.getHeight() != capture.getHeight()) {
                // A reference of the wrong size means the crop rectangle changed; every reference,
                // including this one, is now stale and has to be regenerated.
                throw Grug.fatal("Screenshot reference " + reference.getName() + " in " + referencePath
                        + " is " + image.getWidth() + "x" + image.getHeight() + " but the capture is "
                        + capture.getWidth() + "x" + capture.getHeight()
                        + ". The crop rectangle and every reference image have to agree.");
            }

            int differing = countDifferences(image, capture);
            if (differing == 0) {
                return;
            }
            if (differing < closestDifference) {
                closestDifference = differing;
                closestName = reference.getName();
                closestImage = image;
            }
        }

        if ("true".equals(System.getenv("GRUG_UPDATE_SCREENSHOTS"))) {
            addReference(capture, referenceDirectory, referencePath);
            return;
        }

        File artifact = writeArtifact(capture, referencePath);
        int pixels = capture.getWidth() * capture.getHeight();

        if (references.isEmpty()) {
            throw Grug.fatal("Screenshot mismatch against " + referencePath + ": there are no reference"
                    + " images. The capture was written to " + artifact
                    + "; run with GRUG_UPDATE_SCREENSHOTS=true to accept it.");
        }

        // The closest reference is the useful one to diff against: it keeps the highlighted area as
        // small as possible, so a localized red patch reads as a change while an all-red frame reads
        // as a rendering no reference represents yet. The faded background is the capture either way,
        // since a pixel only counts as matching when the capture and that reference agree there.
        File diff = closestImage == null ? null : writeDiff(capture, closestImage, referencePath);

        throw Grug.fatal("Screenshot mismatch against " + referencePath + ": the capture matches none of the "
                + references.size() + " reference image(s), and differs from the closest (" + closestName
                + ") in " + closestDifference + " of " + pixels + " pixels. The capture was written to "
                + artifact
                + (diff == null ? "." : " and a diff against the closest reference to " + diff + "."));
    }

    /**
     * Checks the reference trees under a mods directory against the {@code screenshots/} convention,
     * returning every violation (empty when the tree is valid). The convention is that, under a
     * mod's {@code screenshots/}, every directory is either a group (subdirectories only) or a
     * reference (only {@code 1.png}, {@code 2.png}, ... with no gaps), the two are never mixed, and
     * a name is exactly a lowercase {@code .png} of a positive number.
     *
     * <p>This is deliberately the only implementation of the rules: the loaders run it before a test
     * run so authors see violations locally, and CI fails through the very same path, so the two
     * can't drift apart.
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
                validateReferenceDirectory(screenshots, modDirectory.getName() + "/screenshots", errors);
            }
        }
        return errors;
    }

    private static void validateReferenceDirectory(File directory, String path, List<String> errors) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }

        List<File> subdirectories = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File entry : entries) {
            (entry.isDirectory() ? subdirectories : files).add(entry);
        }

        if (!subdirectories.isEmpty() && !files.isEmpty()) {
            errors.add(path + " mixes reference PNGs with subdirectories; a directory is either a"
                    + " group of subdirectories or a directory of numbered PNGs, never both.");
        }

        if (!files.isEmpty()) {
            Set<Integer> numbers = new TreeSet<>();
            for (File file : files) {
                if (!REFERENCE_NAME.matcher(file.getName()).matches()) {
                    errors.add(path + "/" + file.getName() + " is not a valid reference name;"
                            + " references are numbered from 1 (1.png, 2.png, ...) with the exact"
                            + " lowercase .png extension.");
                    continue;
                }
                numbers.add(referenceNumber(file));
            }

            int expected = 1;
            for (int number : numbers) {
                if (number != expected) {
                    errors.add(path + " is missing " + expected + ".png, so its references are not"
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

    /** Adds the capture as the next free reference, unless an existing one already matches it. */
    private static void addReference(BufferedImage capture, File directory, String referencePath) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw Grug.fatal("Screenshot update: could not create the reference directory " + referencePath
                    + " (" + directory + ").");
        }
        try {
            File reference = new File(directory, nextNumber(directory) + ".png");
            ImageIO.write(capture, "png", reference);
            System.out.println("[GRUG CI] Wrote screenshot reference " + referencePath + "/"
                    + reference.getName() + " (" + capture.getWidth() + "x" + capture.getHeight() + ")");
        } catch (Exception e) {
            throw Grug.fatal("Screenshot update: failed to write a reference for " + referencePath + ": " + e);
        }
    }

    private static File writeArtifact(BufferedImage capture, String referencePath) {
        try {
            File directory = artifactDirectory(referencePath);
            if (!directory.exists() && !directory.mkdirs()) {
                return new File(directory, "unwritten.png");
            }
            File artifact = new File(directory, nextNumber(directory) + ".png");
            ImageIO.write(capture, "png", artifact);
            return artifact;
        } catch (Exception e) {
            // The artifact is a convenience for collecting missing references; never let failing to
            // write it hide the actual screenshot mismatch.
            return new File(referencePath, "unwritten.png");
        }
    }

    /** Where a capture that matched no reference, and its diff, is stashed for CI to upload. */
    private static File artifactDirectory(String referencePath) {
        return new File(new File(GrugCore.getAdapter().getGameDirectory(), ARTIFACTS_DIRECTORY), referencePath);
    }

    /**
     * Writes a visual diff of the capture against {@code reference}: differing pixels in ImageMagick
     * compare's default highlight color and matching pixels faded towards white, so the red stands
     * out. The colors and the 0xCC alpha are exactly ImageMagick's defaults ({@code #f1001ecc}
     * highlight, {@code #ffffffcc} lowlight) composited over the capture, so this is pixel-identical
     * to {@code magick compare capture reference diff}.
     */
    private static File writeDiff(BufferedImage capture, BufferedImage reference, String referencePath) {
        try {
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
            File directory = artifactDirectory(referencePath);
            if (!directory.exists() && !directory.mkdirs()) {
                return new File(directory, "diff.png");
            }
            File file = new File(directory, "diff.png");
            ImageIO.write(diff, "png", file);
            return file;
        } catch (Exception e) {
            return new File(referencePath, "diff.png");
        }
    }

    /** Composites a highlight/lowlight color over a capture pixel using ImageMagick's 0xCC alpha. */
    private static int blend(int foreground, int background) {
        int inverse = 255 - DIFF_ALPHA;
        int r = (((foreground >> 16) & 0xFF) * DIFF_ALPHA + ((background >> 16) & 0xFF) * inverse + 127) / 255;
        int g = (((foreground >> 8) & 0xFF) * DIFF_ALPHA + ((background >> 8) & 0xFF) * inverse + 127) / 255;
        int b = ((foreground & 0xFF) * DIFF_ALPHA + (background & 0xFF) * inverse + 127) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /** The smallest number at or above 1 not already used, so gaps get filled rather than left. */
    private static int nextNumber(File directory) {
        Set<Integer> used = new HashSet<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (REFERENCE_NAME.matcher(file.getName()).matches()) {
                    used.add(referenceNumber(file));
                }
            }
        }
        int next = 1;
        while (used.contains(next)) {
            next++;
        }
        return next;
    }

    private static BufferedImage read(File file) {
        try {
            return ImageIO.read(file);
        } catch (Exception e) {
            return null;
        }
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
