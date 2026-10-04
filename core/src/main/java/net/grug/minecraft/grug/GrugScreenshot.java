package net.grug.minecraft.grug;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * The payload of a {@code Screenshot} entity: a tolerance percent for the comparison {@code
 * Screenshot.equals} runs, and optionally the rectangle {@code Screenshot.capture} read.
 *
 * <p>Like {@link GrugOption}, this is replaced in place of the object stored under the entity id
 * ({@code Screenshot.tolerance} goes through {@link Grug#addEntityWithId}), rather than a mutable
 * holder. It is a plain class rather than a record because core compiles to Java 8.
 *
 * <p>The captured image is deliberately not part of {@link #equals} and {@link #hashCode}, which
 * compare the tolerance and nothing else. Identity here is "the comparison this entity describes",
 * and two entities built for the same tolerance are the same comparison however many rectangles
 * either has since read; a test that captured something cannot be told apart from one that did not
 * by looking at the tolerance.
 */
@GrugGenerated(
        "record-equivalent: JaCoCo filtered the generated members of the record this replaced")
public final class GrugScreenshot {
    private final double tolerancePercent;
    private final BufferedImage captured;

    public GrugScreenshot(double tolerancePercent) {
        this(tolerancePercent, null);
    }

    public GrugScreenshot(double tolerancePercent, BufferedImage captured) {
        this.tolerancePercent = tolerancePercent;
        this.captured = captured;
    }

    public double tolerancePercent() {
        return tolerancePercent;
    }

    /** The rectangle {@code Screenshot.capture} read, or null when it has not captured one. */
    public BufferedImage captured() {
        return captured;
    }

    /** This comparison's tolerance with {@code captured} attached, for the next entity payload. */
    public GrugScreenshot withCapture(BufferedImage image) {
        return new GrugScreenshot(tolerancePercent, image);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GrugScreenshot)) return false;
        GrugScreenshot other = (GrugScreenshot) o;
        return Double.compare(tolerancePercent, other.tolerancePercent) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tolerancePercent);
    }

    @Override
    public String toString() {
        return "GrugScreenshot[tolerancePercent=" + tolerancePercent + "]";
    }
}
