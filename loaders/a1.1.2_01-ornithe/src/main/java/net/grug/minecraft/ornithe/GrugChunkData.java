package net.grug.minecraft.ornithe;

/**
 * The payload of Alpha's region packet, {@code WorldChunkPacket}.
 *
 * <p>The client reads a region in four passes over the whole region: every column's y-run of block
 * ids, then every column's metadata nibbles, then the block-light nibbles, then the sky-light
 * nibbles ({@code WorldChunk.unpackChunkData}, and the server's own packer mirrors it). Writing one
 * column's four sections before the next column's is not the same layout: the client then reads
 * metadata and light bytes as block ids, and the world it builds ticks those ids against {@code
 * Block.BY_ID}, which has no entry for them, so the client dies in {@code
 * World.doRandomDisplayTicks} (#1486).
 *
 * <p>The read of the cells is behind {@link Source} so the layout is pinned by a unit test, which a
 * dispatch can see only as a dead client.
 */
final class GrugChunkData {

    private GrugChunkData() {}

    /** The cells a region is packed from, in world coordinates. */
    interface Source {
        /** The id of the block at a position. */
        int block(int x, int y, int z);

        /** The metadata of the block at a position. */
        int metadata(int x, int y, int z);

        /** The block light at a position. */
        int blockLight(int x, int y, int z);

        /** The sky light at a position. */
        int skyLight(int x, int y, int z);
    }

    /**
     * The packet's uncompressed payload for a region: its blocks, its metadata nibbles, its
     * block-light nibbles and its sky-light nibbles, each section over the region's whole x and z
     * before the next begins, and each column within a section a y-run. The region has to lie in
     * one chunk column and its height has to be even, which is what the caller builds.
     */
    static byte[] pack(int x, int y, int z, int sizeX, int sizeY, int sizeZ, Source source) {
        byte[] data = new byte[sizeX * sizeY * sizeZ * 5 / 2];
        int index = 0;
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dy = 0; dy < sizeY; dy++) {
                    data[index++] = (byte) source.block(x + dx, y + dy, z + dz);
                }
            }
        }
        int nibbleBytes = sizeY / 2;
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int b = 0; b < nibbleBytes; b++) {
                    int y0 = y + b * 2;
                    data[index++] =
                            packNibbles(
                                    source.metadata(x + dx, y0, z + dz),
                                    source.metadata(x + dx, y0 + 1, z + dz));
                }
            }
        }
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int b = 0; b < nibbleBytes; b++) {
                    int y0 = y + b * 2;
                    data[index++] =
                            packNibbles(
                                    source.blockLight(x + dx, y0, z + dz),
                                    source.blockLight(x + dx, y0 + 1, z + dz));
                }
            }
        }
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int b = 0; b < nibbleBytes; b++) {
                    int y0 = y + b * 2;
                    data[index++] =
                            packNibbles(
                                    source.skyLight(x + dx, y0, z + dz),
                                    source.skyLight(x + dx, y0 + 1, z + dz));
                }
            }
        }
        return data;
    }

    /** Two values of a nibble section as one byte, the even y in the low nibble. */
    private static byte packNibbles(int lower, int upper) {
        return (byte) ((upper << 4) | (lower & 0xF));
    }
}
