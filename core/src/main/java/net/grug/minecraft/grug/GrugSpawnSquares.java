package net.grug.minecraft.grug;

import net.grug.minecraft.core.ModLoaderAdapter;

/**
 * The squares a cube scan kept, as one flat list per field: the position, the block light and the
 * sky light of every square a hostile mob may stand in.
 *
 * <p>The scan is the reference's own: a cube of {@code radius} blocks each way on every axis around
 * the player's feet block, position by position, skipping the ones outside the level's height limit
 * and keeping the ones the game's spawner accepts as a place to stand. It is one host call rather
 * than one per block because the per-block calls a scan of a 33 a side cube would make cost a
 * frame, while the same work inside the host is a few loop iterations over the level's own arrays.
 * The mod still decides which of the kept squares to mark, because that is the mod's rule and not
 * the host's.
 *
 * <p>The order is the reference's {@code Checker}: z, then y, then x, each from the lowest
 * coordinate, so a mod that draws in list order walks the cube the same way the reference's packed
 * grid does. Instances are immutable and safe to hand to a script that keeps them.
 */
public final class GrugSpawnSquares {
    /**
     * The largest radius a scan accepts. A radius of 16 is 35937 positions, which is what the
     * reference's default render distance is, and each one reads the world three times; a radius of
     * 32 is 274625 positions, already far more than a frame can spend. A cube larger than that is
     * refused rather than stalling the game.
     */
    public static final int MAX_RADIUS = 32;

    private final int[] x;
    private final int[] y;
    private final int[] z;
    private final int[] blockLight;
    private final int[] skyLight;
    private final int count;

    GrugSpawnSquares(int[] x, int[] y, int[] z, int[] blockLight, int[] skyLight, int count) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.count = count;
    }

    public int count() {
        return count;
    }

    public int x(int index) {
        return x[index];
    }

    public int y(int index) {
        return y[index];
    }

    public int z(int index) {
        return z[index];
    }

    public int blockLight(int index) {
        return blockLight[index];
    }

    public int skyLight(int index) {
        return skyLight[index];
    }

    /**
     * Scans the cube around {@code (centerX, centerY, centerZ)}, the player's feet block, keeping
     * every position the game accepts as a spawn space.
     *
     * <p>The centre is taken as it is handed in, so the caller decides how a fractional coordinate
     * is rounded. Every caller floors it, which is the block the feet are in and what {@code
     * MathHelper.floor_double} answers: a feet x of -18.5 scans from -19, not -18.
     */
    public static GrugSpawnSquares scan(
            ModLoaderAdapter adapter,
            Object level,
            int centerX,
            int centerY,
            int centerZ,
            int radius) {
        int side = radius * 2 + 1;
        int capacity = Math.min(side * side * side, 1024);
        int[] x = new int[capacity];
        int[] y = new int[capacity];
        int[] z = new int[capacity];
        int[] blockLight = new int[capacity];
        int[] skyLight = new int[capacity];
        int count = 0;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int worldX = centerX + dx;
                    int worldY = centerY + dy;
                    int worldZ = centerZ + dz;
                    if (adapter.isOutOfHeightLimit(level, worldY)) continue;
                    if (!adapter.isHostileSpawnSpace(level, worldX, worldY, worldZ)) continue;
                    if (count == x.length) {
                        int grown = x.length * 2;
                        x = grow(x, grown);
                        y = grow(y, grown);
                        z = grow(z, grown);
                        blockLight = grow(blockLight, grown);
                        skyLight = grow(skyLight, grown);
                    }
                    x[count] = worldX;
                    y[count] = worldY;
                    z[count] = worldZ;
                    blockLight[count] = adapter.getBlockLight(level, worldX, worldY, worldZ);
                    skyLight[count] = adapter.getSkyLight(level, worldX, worldY, worldZ);
                    count++;
                }
            }
        }

        return new GrugSpawnSquares(x, y, z, blockLight, skyLight, count);
    }

    private static int[] grow(int[] values, int length) {
        int[] grown = new int[length];
        System.arraycopy(values, 0, grown, 0, values.length);
        return grown;
    }
}
