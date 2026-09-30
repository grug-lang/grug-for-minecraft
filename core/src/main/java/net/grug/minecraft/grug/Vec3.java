package net.grug.minecraft.grug;

@GrugGenerated(
        "record-equivalent: JaCoCo filtered the generated members of the record this replaced")
public final class Vec3 {
    private final double x;
    private final double y;
    private final double z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Vec3)) return false;
        Vec3 other = (Vec3) o;
        return Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0;
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(x);
        result = 31 * result + Double.hashCode(y);
        result = 31 * result + Double.hashCode(z);
        return result;
    }

    @Override
    public String toString() {
        return "Vec3[x=" + x + ", y=" + y + ", z=" + z + "]";
    }
}
