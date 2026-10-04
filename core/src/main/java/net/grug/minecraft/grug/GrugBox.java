package net.grug.minecraft.grug;

/**
 * One axis-aligned box in block-local coordinates, as the draw_box host function recorded it during
 * a block entity's render pass.
 *
 * <p>The corners run from 0 to 1 on each axis, measured from the block's minimum corner, so a
 * loader adds the block's own position to them to get world coordinates. x is west to east, y is
 * bottom to top and z is north to south, matching every other coordinate grug passes.
 *
 * <p>Two boxes are equal when their corners are, so a test can say what a shape drew without
 * walking a list by index.
 */
public final class GrugBox {
    private final double x1;
    private final double y1;
    private final double z1;
    private final double x2;
    private final double y2;
    private final double z2;

    public GrugBox(double x1, double y1, double z1, double x2, double y2, double z2) {
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;
    }

    public double x1() {
        return x1;
    }

    public double y1() {
        return y1;
    }

    public double z1() {
        return z1;
    }

    public double x2() {
        return x2;
    }

    public double y2() {
        return y2;
    }

    public double z2() {
        return z2;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GrugBox)) return false;
        GrugBox other = (GrugBox) o;
        return Double.compare(x1, other.x1) == 0
                && Double.compare(y1, other.y1) == 0
                && Double.compare(z1, other.z1) == 0
                && Double.compare(x2, other.x2) == 0
                && Double.compare(y2, other.y2) == 0
                && Double.compare(z2, other.z2) == 0;
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(x1);
        result = 31 * result + Double.hashCode(y1);
        result = 31 * result + Double.hashCode(z1);
        result = 31 * result + Double.hashCode(x2);
        result = 31 * result + Double.hashCode(y2);
        result = 31 * result + Double.hashCode(z2);
        return result;
    }

    @Override
    public String toString() {
        return "GrugBox[" + x1 + ", " + y1 + ", " + z1 + " -> " + x2 + ", " + y2 + ", " + z2 + "]";
    }
}
