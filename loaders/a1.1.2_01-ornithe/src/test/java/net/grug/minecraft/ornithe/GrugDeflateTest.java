package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Whether a packet's compressed payload is the whole stream (#1522).
 *
 * <p>A region that holds more noise than repetition compresses to more bytes than it holds, and the
 * one {@code deflate} call the adapter used to make wrote only as much of it as its right-sized
 * buffer could take. The length the call returned was announced as the compressed size, so the
 * client inflated a stream that stopped before its end. These fixtures are the two kinds of region,
 * one the compressor grows and one it shrinks.
 */
class GrugDeflateTest {

    @Test
    void oneDeflateCallIntoTheRawSizesBufferStopsBeforeTheStreamEnds() {
        // What the adapter used to do, so the fixture below is known to be the truncation case: the
        // buffer is full and the deflater still holds bytes, and the announced size was the
        // buffer's.
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(noise(64));
            deflater.finish();
            byte[] buffer = new byte[64];

            deflater.deflate(buffer);

            assertFalse(
                    deflater.finished(),
                    "one call into a raw-sized buffer should leave part of this stream undeflated");
        } finally {
            deflater.end();
        }
    }

    @Test
    void aRegionThatCompressesLargerThanItHoldsRoundTripsWhole() throws DataFormatException {
        byte[] raw = noise(64);

        byte[] compressed = GrugDeflate.deflate(raw);

        assertTrue(
                compressed.length > raw.length,
                "this fixture is only the truncation case if deflating it grows the stream");
        assertArrayEquals(raw, inflate(compressed), "the whole stream has to reach the client");
    }

    @Test
    void aRegionThatCompressesSmallerThanItHoldsRoundTrips() throws DataFormatException {
        byte[] raw = new byte[4096];

        byte[] compressed = GrugDeflate.deflate(raw);

        assertTrue(compressed.length < raw.length, "a run of one value should shrink");
        assertArrayEquals(raw, inflate(compressed));
    }

    /** Bytes that hold no repetition, so deflating them grows the stream. */
    private static byte[] noise(int length) {
        byte[] raw = new byte[length];
        new Random(1522).nextBytes(raw);
        return raw;
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
