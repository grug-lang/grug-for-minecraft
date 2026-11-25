package net.grug.minecraft.core;

/**
 * The light a joined Beta client has to recompute after a region packet (#1740).
 *
 * <p>A Beta server sends a chunk's whole column when a client first receives it, and a
 * <em>region</em> packet once the blocks of one chunk changed. The client applies either by copying
 * the blocks and the light bytes the packet carries ({@code Chunk.loadFromPacket}, or {@code
 * WorldChunk.unpackChunkData} in the same version's other mappings) and then recomputing the
 * chunk's height map, with no light update queued for the cells that recompute leaves stale. A
 * packet that raised a column's height therefore leaves every cell between the old height and the
 * new one at the light of the open column: the cell just above a room's ceiling keeps the sky's 15
 * while the platform twenty blocks above the floor makes the server's 14, and no later packet
 * covers that cell, so re-sending the region does not repair it either.
 *
 * <p>The range a block change can alter is the one vanilla queues when the block arrives on its
 * own: {@code Chunk.updateHeightMap} recomputes the changed column's sky from its old height to its
 * new one, and {@code Chunk.lightGaps} does the same for the columns beside it. This class gathers
 * that range for every column whose height the packet changed, as one box: one column around each,
 * and from the lower of the column's two heights to the higher. The caller recomputes both fields
 * of that box until they stop changing, which is the field the client's own blocks define.
 *
 * <p>The first delivery of a chunk is the one packet this is not needed for: it covers the whole
 * column, so its light bytes are the server's field for every cell of it and the client already
 * holds what its blocks define.
 *
 * <p>Pure, so the unit test pins the box, the column that did not move, the world's edges, and the
 * packet that covers a whole column.
 */
public final class GrugChunkRelight {
    /** Columns per side of a Beta chunk. */
    public static final int COLUMNS = 16;

    /** The height of every Beta world this runs on, in blocks. */
    private static final int WORLD_HEIGHT = 128;

    private GrugChunkRelight() {}

    /**
     * Whether the packet covers a whole column of the world and so carries the server's light for
     * every cell of it, which a client's first delivery of a chunk does: the packet's y range
     * reaches both ends of the world, so there is no cell under it whose light the packet left
     * stale.
     */
    public static boolean isWholeColumn(int y, int sizeY) {
        return y <= 0 && y + sizeY >= WORLD_HEIGHT;
    }

    /**
     * A column's index in the height arrays, in the order {@code Chunk.getHeight} stores them: the
     * local z shifted up four and the local x added.
     */
    public static int column(int localX, int localZ) {
        return localZ * COLUMNS + localX;
    }

    /**
     * The box to recompute as {@code {minX, minY, minZ, maxX, maxY, maxZ}} in world blocks, or null
     * when no column's height changed and the client already holds the field its blocks define.
     *
     * <p>The packet recomputes every column of its chunk, not only the ones it carried blocks for:
     * a block a client was told about separately is already in its chunk's block array, so its
     * column's height moves on whichever region packet next recomputes the chunk. The caller
     * therefore hands over the chunk's whole height map, before and after.
     *
     * <p>A column whose height did not change is not recorded: its cells keep the light they had,
     * and a neighbour's light does not depend on it having moved.
     */
    public static int[] box(int[] before, int[] after, int chunkX, int chunkZ) {
        if (before.length != COLUMNS * COLUMNS || after.length != before.length) {
            throw new IllegalArgumentException("a chunk holds " + (COLUMNS * COLUMNS) + " columns");
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int localZ = 0; localZ < COLUMNS; localZ++) {
            for (int localX = 0; localX < COLUMNS; localX++) {
                int index = column(localX, localZ);
                int was = before[index];
                int now = after[index];
                if (was == now) {
                    continue;
                }
                int x = (chunkX << 4) + localX;
                int z = (chunkZ << 4) + localZ;
                minX = Math.min(minX, x - 1);
                minY = Math.min(minY, Math.min(was, now));
                minZ = Math.min(minZ, z - 1);
                maxX = Math.max(maxX, x + 1);
                maxY = Math.max(maxY, Math.max(was, now));
                maxZ = Math.max(maxZ, z + 1);
            }
        }
        if (minX > maxX) {
            return null;
        }
        // A height counts the blocks below it, so the highest cell is one under the world's top.
        return new int[] {minX, minY, minZ, maxX, Math.min(maxY, WORLD_HEIGHT - 1), maxZ};
    }
}
