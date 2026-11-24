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
    private int[] sent = new int[0];

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
     * The box {@code {minX, minY, minZ, maxX, maxY, maxZ}} the regions {@link #due} last returned
     * were cut from, or an empty array before it has returned any. The loader recomputes its light
     * over this box before it sends, so the light that goes out is the field the final blocks
     * define and not the one the order of the placements left, and the box is what the light ask
     * then compares ({@link GrugLightAsk}). Its height is whole pairs of rows, which is what a
     * region packet's light copy covers.
     */
    public synchronized int[] lastBox() {
        return sent.clone();
    }

    /**
     * Arms the regions {@link #due} last returned again, because the joined client's light came
     * back different from this server's over that box (#1695).
     *
     * <p>A client that relit a block change after the region packet landed holds a field the
     * server's engine never produced, and nothing else would replace it: the placements are no
     * longer owed, so {@link #due} would send nothing and the divergence would be permanent. The
     * box is the one that was sent, so re-arming neither grows it nor forgets a change noted since
     * (that one has already widened it). One poll arms the send and the next makes it, as after a
     * change, so the client is given a tick before the region it asked for arrives.
     */
    public synchronized void rearm() {
        if (sent.length != 6) {
            return;
        }
        owed = true;
        waited = false;
        minX = sent[0];
        minY = sent[1];
        minZ = sent[2];
        maxX = sent[3];
        maxY = sent[4];
        maxZ = sent[5];
    }

    /**
     * Forgets the box this test owes or last sent, for the next test's room build.
     *
     * <p>A test that never waits on the world leaves the blocks it placed owed, and {@link #due}
     * unions them with the next test's changes: the box then spans every placement since the last
     * wait, a quarry a hundred blocks east included, and every untouched cell between them. The
     * joined client lit those cells for itself, and a differing cell there is neither one the
     * waiting test changed nor one a capture of it reads, so the wait would report a divergence the
     * test cannot fix (#1695). The next test's room and placements are noted after this, so the box
     * its waits compare is the one its own changes made.
     */
    public synchronized void forget() {
        owed = false;
        waited = false;
        sent = new int[0];
    }

    /**
     * The regions to send the client now, one per chunk the changes touched, each as {@code {x, y,
     * z, sizeX, sizeY, sizeZ}} in blocks, or an empty list while nothing is owed or the tick that
     * made the change has not ended. Clears what it returns.
     *
     * <p>The height is widened to whole pairs of rows, since a region packet copies a column's
     * light one byte per pair: a range of an odd height or an odd lower edge would leave its top
     * row as the client had it, and the light ask would compare a cell the server never sent.
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
        int fromY = Math.max(minY, 0) >> 1 << 1;
        int toY = (Math.min(maxY, WORLD_HEIGHT - 1) >> 1 << 1) + 1;
        if (fromY > toY) {
            sent = new int[0];
            return regions;
        }
        sent = new int[] {minX, fromY, minZ, maxX, toY, maxZ};
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
