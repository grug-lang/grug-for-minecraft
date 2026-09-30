package net.grug.minecraft.grug;

@GrugGenerated(
        "record-equivalent: JaCoCo filtered the generated members of the record this replaced")
public final class BlockPos {
    private final int x;
    private final int y;
    private final int z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockPos)) return false;
        BlockPos other = (BlockPos) o;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + z;
        return result;
    }

    @Override
    public String toString() {
        return "BlockPos[x=" + x + ", y=" + y + ", z=" + z + "]";
    }
}
