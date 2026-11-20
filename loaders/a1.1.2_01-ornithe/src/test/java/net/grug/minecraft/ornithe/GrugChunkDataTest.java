package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * The byte order of a region packet's payload (#1486).
 *
 * <p>The client reads a region in four passes over the whole region, not one column at a time:
 * every column's y-run of block ids, then every column's metadata nibbles, then the block-light
 * nibbles, then the sky-light nibbles ({@code WorldChunk.unpackChunkData}). The a1 dedicated
 * server's first proof run after the resend landed sent the four sections of one column before the
 * next column's, so the client read metadata and light bytes as block ids and died in {@code
 * Block.BY_ID} during {@code World.doRandomDisplayTicks}, which left the {@code screen_open}
 * request unanswered (run 44821477066).
 *
 * <p>The region is 2 by 2 by 2 at the origin so that each section is several columns long, which is
 * what an interleaved payload gets wrong.
 */
class GrugChunkDataTest {

    /** Cells whose four sections hold different values, so a mixed-up section cannot pass. */
    private static final class Cells implements GrugChunkData.Source {

        @Override
        public int block(int x, int y, int z) {
            return 1 + x * 100 + y * 10 + z;
        }

        @Override
        public int metadata(int x, int y, int z) {
            return x * 8 + y * 4 + z;
        }

        @Override
        public int blockLight(int x, int y, int z) {
            return x * 4 + z * 2 + y;
        }

        @Override
        public int skyLight(int x, int y, int z) {
            return 15 - (x * 4 + z * 2 + y);
        }
    }

    @Test
    void theFourSectionsFollowEachOtherAndEachColumnIsAYRun() {
        byte[] packed = GrugChunkData.pack(0, 0, 0, 2, 2, 2, new Cells());

        assertArrayEquals(
                new byte[] {
                    // blocks, x outer and z inner, each column a y-run
                    1,
                    11,
                    2,
                    12,
                    101,
                    111,
                    102,
                    112,
                    // the metadata nibbles, the even y in the low nibble of each column's byte
                    0x40,
                    0x51,
                    (byte) 0xC8,
                    (byte) 0xD9,
                    // the block-light nibbles
                    0x10,
                    0x32,
                    0x54,
                    0x76,
                    // the sky-light nibbles
                    (byte) 0xEF,
                    (byte) 0xCD,
                    (byte) 0xAB,
                    (byte) 0x89,
                },
                packed);
    }
}
