package net.grug.minecraft.ornithe;

import net.grug.minecraft.grug.Grug;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;

/**
 * The compressed payload of a packet, deflated in full.
 *
 * <p>One {@code deflate} call into a buffer the size of the raw payload is not the whole stream: a
 * region small enough to be mostly noise, such as the one-cell regions a test's resend can cover,
 * deflates to more bytes than it holds, and the call then returns as much as the buffer can take
 * and stops. The packet announced the returned length as its compressed size, so the client read
 * past what the array held (#1522). The stream is therefore taken until the deflater says it is
 * finished, and the size is the length of what was written.
 */
final class GrugDeflate {

    /** The buffer one step of the loop fills. */
    private static final int BUFFER_SIZE = 4096;

    private GrugDeflate() {}

    /** The deflated form of {@code raw}, complete however much larger than it that is. */
    static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            return drain(
                    new Stream() {
                        @Override
                        public int write(byte[] buffer) {
                            return deflater.deflate(buffer);
                        }

                        @Override
                        public boolean finished() {
                            return deflater.finished();
                        }
                    },
                    raw.length);
        } finally {
            deflater.end();
        }
    }

    /**
     * Everything the stream writes, until it says it is finished.
     *
     * <p>A step that writes nothing while the stream has not ended would spin this loop forever.
     * The JDK's deflater answers that only when it wants input or a dictionary, neither of which
     * this caller can give it after handing the whole payload over, so it is a broken invariant
     * rather than a region to compress (#1522).
     */
    static byte[] drain(Stream stream, int rawLength) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(rawLength / 2 + 16);
        byte[] buffer = new byte[BUFFER_SIZE];
        while (!stream.finished()) {
            int written = stream.write(buffer);
            if (written == 0) {
                throw Grug.fatal(
                        "a packet payload's deflater wrote nothing while its stream had not ended");
            }
            compressed.write(buffer, 0, written);
        }
        return compressed.toByteArray();
    }

    /** A deflater's stream, as {@link #drain} reads it. */
    interface Stream {
        /** Writes the next part of the stream into {@code buffer}, and answers how much. */
        int write(byte[] buffer);

        /** Whether the stream has ended. */
        boolean finished();
    }
}
