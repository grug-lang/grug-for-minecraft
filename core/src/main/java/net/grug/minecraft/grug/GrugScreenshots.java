package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reference-image handling shared by every loader's {@code Test.assert_screenshot_equals()}.
 *
 * <p>The reference path names a directory of numbered PNGs ({@code 0.png}, {@code 1.png}, ...). The
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

    private static final Pattern REFERENCE_NAME = Pattern.compile("[0-9]+\\.png");

    /** Where a capture that matched no reference is stashed for CI to upload. */
    private static final String ARTIFACTS_DIRECTORY = "grug-screenshot-artifacts";

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

        throw Grug.fatal("Screenshot mismatch against " + referencePath + ": the capture matches none of the "
                + references.size() + " reference image(s), and differs from the closest (" + closestName
                + ") in " + closestDifference + " of " + pixels + " pixels. The capture was written to "
                + artifact + ".");
    }

    /** The numbered references in a directory, ordered 0, 1, 2, ... If it doesn't exist, empty. */
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
            File directory = new File(new File(GrugCore.getAdapter().getGameDirectory(), ARTIFACTS_DIRECTORY),
                    referencePath);
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

    private static int nextNumber(File directory) {
        int next = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (REFERENCE_NAME.matcher(file.getName()).matches()) {
                    next = Math.max(next, referenceNumber(file) + 1);
                }
            }
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
