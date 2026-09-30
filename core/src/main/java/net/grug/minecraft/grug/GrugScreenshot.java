package net.grug.minecraft.grug;

import java.util.Objects;

/**
 * The payload of a {@code Screenshot} entity: a tolerance percent for the comparison {@code
 * Screenshot.equals} runs.
 *
 * <p>Like {@link GrugOption}, this is replaced in place of the object stored under the entity id
 * ({@code Screenshot.tolerance} goes through {@link Grug#addEntityWithId}), rather than a mutable
 * holder. It is a plain class rather than a record because core compiles to Java 8.
 */
@GrugGenerated(
        "record-equivalent: JaCoCo filtered the generated members of the record this replaced")
public final class GrugScreenshot {
    private final double tolerancePercent;

    public GrugScreenshot(double tolerancePercent) {
        this.tolerancePercent = tolerancePercent;
    }

    public double tolerancePercent() {
        return tolerancePercent;
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
