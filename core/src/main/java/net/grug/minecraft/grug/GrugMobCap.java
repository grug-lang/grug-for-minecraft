package net.grug.minecraft.grug;

import java.util.HashSet;
import java.util.Set;

/**
 * The part of Beta 1.7.3's mob cap that does not depend on a loader's mappings (#1252). The game's
 * spawner lets a category spawn while the world's count of it is at most {@code cap * chunks / 256}
 * in integer arithmetic, where {@code chunks} is the number of distinct chunks within 8 chunks of
 * any player, and {@code cap} is 70 for hostile mobs, 15 for passive ones and 5 for water ones.
 */
public final class GrugMobCap {
    private GrugMobCap() {}

    /** The category names a mod passes, in the order of the game's own enum. */
    public static final String[] CATEGORIES = {"hostile", "passive", "water"};

    /** The game's cap of each category in {@link #CATEGORIES} order, before the chunk scaling. */
    private static final int[] BASE_CAPS = {70, 15, 5};

    /** The index of the category in {@link #CATEGORIES}, or -1 when the name is not one. */
    public static int indexOf(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(category)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The number of distinct chunks within 8 chunks, square, of any of the players, whose chunk
     * coordinates are {@code chunkXs[i], chunkZs[i]}: two players near one another share chunks.
     */
    public static int eligibleChunks(int[] chunkXs, int[] chunkZs) {
        Set<Long> chunks = new HashSet<>();
        for (int i = 0; i < chunkXs.length; i++) {
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    chunks.add(
                            ((long) (chunkXs[i] + dx) << 32) | ((chunkZs[i] + dz) & 0xFFFFFFFFL));
                }
            }
        }
        return chunks.size();
    }

    /** The cap of the category at the index, for that many eligible chunks. */
    public static int cap(int categoryIndex, int eligibleChunks) {
        return BASE_CAPS[categoryIndex] * eligibleChunks / 256;
    }
}
