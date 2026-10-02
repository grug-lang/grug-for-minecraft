package net.grug.minecraft.core;

/**
 * Whether a client has received the chunks a test setup builds in.
 *
 * <p>A chunk the client has not received reads as air all the way down, so any non-air block in a
 * column proves that its data arrived. The probe checks the player's own column and the nearest
 * column of each of the four side neighbouring chunks, because the coverage tests build up to 9
 * blocks away, far enough to cross a chunk border.
 */
public final class GrugWorldReady {
    private GrugWorldReady() {}

    /** Reads one block position from a level, so the probe does not depend on a loader. */
    public interface BlockSource {
        boolean isAir(double x, double y, double z);
    }

    /**
     * @param x the player's x
     * @param feetY the bottom of the player's collision box; the old loaders keep the entity
     *     position at eye height, so callers there must not pass the raw y
     * @param z the player's z
     */
    public static boolean isReady(BlockSource blocks, double x, double feetY, double z) {
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(feetY);
        int blockZ = (int) Math.floor(z);
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        return columnHasTerrain(blocks, blockX, blockZ, blockY)
                && columnHasTerrain(blocks, (chunkX + 1) << 4, blockZ, blockY)
                && columnHasTerrain(blocks, ((chunkX - 1) << 4) + 15, blockZ, blockY)
                && columnHasTerrain(blocks, blockX, (chunkZ + 1) << 4, blockY)
                && columnHasTerrain(blocks, blockX, ((chunkZ - 1) << 4) + 15, blockY);
    }

    private static boolean columnHasTerrain(BlockSource blocks, int x, int z, int fromY) {
        for (int y = fromY; y >= 0; y--) {
            if (!blocks.isAir(x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
