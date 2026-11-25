package net.grug.minecraft.ornithe;

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

    private GrugDeflate() {}

    /** The deflated form of {@code raw}, complete however much larger than it that is. */
    static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(raw.length / 2 + 16);
            byte[] buffer = new byte[4096];
            while (!deflater.finished()) {
                compressed.write(buffer, 0, deflater.deflate(buffer));
            }
            return compressed.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
