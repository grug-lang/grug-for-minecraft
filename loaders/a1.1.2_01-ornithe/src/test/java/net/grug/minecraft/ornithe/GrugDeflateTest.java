package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;
import net.minecraft.network.packet.WorldChunkPacket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Whether the region packet carries its whole compressed payload (#1522).
 *
 * <p>A region that holds more noise than repetition compresses to more bytes than it holds. The
 * adapter used to fill a buffer the size of the raw region with one {@code deflate} call and
 * announce the returned length as the compressed size, so the client inflated a stream that stopped
 * before its end. These tests build the packet through the production path and check both the size
 * it announces and the bytes a client inflates.
 */
class GrugDeflateTest {

    /** One chunk column, two blocks high: 5 raw bytes, which deflate always grows. */
    private static final int[] TINY_REGION = {0, 0, 0, 1, 2, 1};

    /** One chunk column, 32 blocks high: 5120 raw bytes, more than one write of the loop fills. */
    private static final int[] REGION_LARGER_THAN_ONE_WRITE = {0, 0, 0, 8, 32, 8};

    @Test
    void aPacketOverAnIncompressibleRegionAnnouncesAndCarriesItsWholeStream()
            throws DataFormatException, ReflectiveOperationException {
        GrugChunkData.Source cells = noiseCells(1522L);

        WorldChunkPacket packet = ServerOrnitheAdapter.buildWorldChunkPacket(TINY_REGION, cells);
        byte[] raw = GrugChunkData.pack(0, 0, 0, 1, 2, 1, cells);

        assertTrue(
                packet.data.length > raw.length,
                "this fixture is only the truncation case if deflating it grows the payload");
        assertEquals(
                packet.data.length,
                compressedSize(packet),
                "the packet has to announce the size of the data it carries");
        assertArrayEquals(raw, inflate(packet.data), "the whole stream has to reach the client");
    }

    @Test
    void aPacketWhoseStreamNeedsMoreThanOneWriteRoundTripsWhole()
            throws DataFormatException, ReflectiveOperationException {
        GrugChunkData.Source cells = noiseCells(1522L);

        WorldChunkPacket packet =
                ServerOrnitheAdapter.buildWorldChunkPacket(REGION_LARGER_THAN_ONE_WRITE, cells);
        byte[] raw = GrugChunkData.pack(0, 0, 0, 8, 32, 8, cells);

        assertTrue(raw.length > 4096, "the payload has to need more than one write of the loop");
        assertEquals(packet.data.length, compressedSize(packet));
        assertArrayEquals(raw, inflate(packet.data));
    }

    @Test
    void anEmptyPayloadIsAFinishedStreamThatInflatesBackToNothing() throws DataFormatException {
        byte[] compressed = GrugDeflate.deflate(new byte[0]);

        assertArrayEquals(
                new byte[0],
                inflate(compressed),
                "an empty payload still has to be a whole stream");
    }

    @Test
    void aStreamThatWritesNothingBeforeItEndsIsAnInvariant() {
        GrugDeflate.Stream stuck =
                new GrugDeflate.Stream() {
                    @Override
                    public int write(byte[] buffer) {
                        return 0;
                    }

                    @Override
                    public boolean finished() {
                        return false;
                    }
                };

        assertThrows(
                IllegalStateException.class,
                () -> GrugDeflate.drain(stuck, 1),
                "a step that writes nothing would spin the loop, so it has to fail");
        // Grug.fatal remembers the exception per thread until a native call returns, so this test
        // clears it before another test on the same thread can be crashed by it.
        assertThrows(IllegalStateException.class, GrugErrors::throwPendingFatal);
    }

    /** The compressed size the packet announces, which the game keeps private. */
    private static int compressedSize(WorldChunkPacket packet) throws ReflectiveOperationException {
        Field field = WorldChunkPacket.class.getDeclaredField("compressedSize");
        field.setAccessible(true);
        return field.getInt(packet);
    }

    /**
     * Cells whose values are a fixed function of their position, so the packet and the raw payload
     * built afterwards hold the same bytes.
     */
    private static GrugChunkData.Source noiseCells(final long seed) {
        return new GrugChunkData.Source() {
            @Override
            public int block(int x, int y, int z) {
                return (int) (hash(seed, x, y, z) & 0xFF);
            }

            @Override
            public int metadata(int x, int y, int z) {
                return (int) (hash(seed + 1, x, y, z) & 0xF);
            }

            @Override
            public int blockLight(int x, int y, int z) {
                return (int) (hash(seed + 2, x, y, z) & 0xF);
            }

            @Override
            public int skyLight(int x, int y, int z) {
                return (int) (hash(seed + 3, x, y, z) & 0xF);
            }
        };
    }

    /** A position-dependent value with no repetition between neighbours. */
    private static long hash(long seed, int x, int y, int z) {
        long value = seed;
        value = value * 0x9E3779B97F4A7C15L + x;
        value = value * 0x9E3779B97F4A7C15L + y;
        value = value * 0x9E3779B97F4A7C15L + z;
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return value;
    }

    /** What the client does with a payload: inflate it whole. */
    private static byte[] inflate(byte[] compressed) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int written = inflater.inflate(buffer);
                if (written == 0) {
                    break;
                }
                raw.write(buffer, 0, written);
            }
            return raw.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
