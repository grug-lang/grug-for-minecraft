package net.grug.minecraft.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks a dedicated server changed whose light a joined client has to be sent again, because
 * the client lights its own copy incrementally and the server's light is the one a capture's golden
 * was made from.
 *
 * <p>A Beta server sends a chunk's changes as a block-change packet while few blocks changed and as
 * a region packet that carries light once ten or more did. A client applies a block-change packet
 * by changing the block and lighting its own copy from that change alone, so a chunk a room touches
 * with a handful of blocks is lit without the light that the room's other chunks spill into it, and
 * the light of such an edge differs by a level from the server's, whose engine saw every change.
 * The client-only run, whose world is one copy, lit it as the server did, so a server-driven
 * capture shows a lighter or darker band at the edge that the client-only golden does not (#1092).
 *
 * <p>Region packets are the one way this version carries light to a client, so the loader sends the
 * region the changes touched again once the server's light has settled. The server flushes a tick's
 * block changes to its clients at the end of the tick, and a region sent before that flush would be
 * overwritten by the client's own relight of the block-change packet that follows it. {@link #due}
 * therefore answers nothing for the poll that follows a change and the region on the next one.
 *
 * <p>Pure, so that its unit test covers the box, the wait and the split into chunks, none of which
 * a dispatch can pin down.
 */
public final class GrugLightResend {
    /** The height of this version's world, in blocks. */
    private static final int WORLD_HEIGHT = 128;

    private boolean owed;
    private boolean waited;
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    /**
     * Records that the block at this position changed, and with it the light of the blocks around
     * it. Starts the wait over, because a change made now is flushed to the client at the end of
     * the tick and the region has to follow that.
     */
    public synchronized void noteBlock(int x, int y, int z) {
        if (owed) {
            minX = Math.min(minX, x - 1);
            minY = Math.min(minY, y - 1);
            minZ = Math.min(minZ, z - 1);
            maxX = Math.max(maxX, x + 1);
            maxY = Math.max(maxY, y + 1);
            maxZ = Math.max(maxZ, z + 1);
        } else {
            owed = true;
            minX = x - 1;
            minY = y - 1;
            minZ = z - 1;
            maxX = x + 1;
            maxY = y + 1;
            maxZ = z + 1;
        }
        waited = false;
    }

    /** Whether a region is still owed to the client, for a settle answer that must say no. */
    public synchronized boolean pending() {
        return owed;
    }

    /**
     * The regions to send the client now, one per chunk the changes touched, each as {@code {x, y,
     * z, sizeX, sizeY, sizeZ}} in blocks, or an empty list while nothing is owed or the tick that
     * made the change has not ended. Clears what it returns.
     */
    public synchronized List<int[]> due() {
        List<int[]> regions = new ArrayList<>();
        if (!owed) {
            return regions;
        }
        if (!waited) {
            waited = true;
            return regions;
        }
        owed = false;
        waited = false;
        int fromY = Math.max(minY, 0);
        int toY = Math.min(maxY, WORLD_HEIGHT - 1);
        if (fromY > toY) {
            return regions;
        }
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int fromX = Math.max(minX, chunkX << 4);
                int toX = Math.min(maxX, (chunkX << 4) + 15);
                int fromZ = Math.max(minZ, chunkZ << 4);
                int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
                regions.add(
                        new int[] {
                            fromX, fromY, fromZ, toX - fromX + 1, toY - fromY + 1, toZ - fromZ + 1
                        });
            }
        }
        return regions;
    }
}
