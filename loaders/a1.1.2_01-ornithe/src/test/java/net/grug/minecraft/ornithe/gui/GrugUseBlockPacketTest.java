package net.grug.minecraft.ornithe.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * What the forwarded click carries and how much room it takes (#1494).
 *
 * <p>The game reads a packet straight off the connection with no length in front of it, so the body
 * a writer produces is the whole of what a reader may consume, and {@code getSize} is what the
 * connection counts against its send buffer. A joined client's click only the dispatch-only
 * multiplayer job reaches, so the position and the size are pinned here.
 */
class GrugUseBlockPacketTest {

    @AfterEach
    void forgetTheClientHalf() {
        GrugUseBlockPacket.clientHandler = null;
    }

    @Test
    void thePositionSurvivesTheWire() throws IOException {
        GrugUseBlockPacket packet = new GrugUseBlockPacket(-12, 64, 300);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body(packet)));

        GrugUseBlockPacket read = new GrugUseBlockPacket();
        read.read(in);

        assertEquals(-1, in.read(), "the body has to be exactly the bytes the writer wrote");
        int[] position = new int[3];
        GrugUseBlockPacket.clientHandler =
                (x, y, z) -> {
                    position[0] = x;
                    position[1] = y;
                    position[2] = z;
                };
        read.handle(null);
        assertEquals(-12, position[0]);
        assertEquals(64, position[1]);
        assertEquals(300, position[2]);
    }

    @Test
    void theSizeIsTheBodyTheConnectionWrites() throws IOException {
        assertEquals(
                body(new GrugUseBlockPacket(1, 2, 3)).length, new GrugUseBlockPacket().getSize());
    }

    @Test
    void aSideWithoutTheClientHalfDoesNothing() {
        GrugUseBlockPacket.clientHandler = null;
        assertNull(GrugUseBlockPacket.clientHandler, "a server installs no handler");
        new GrugUseBlockPacket(1, 2, 3).handle(null);
    }

    /** The bytes the game would put on the connection after the id. */
    private static byte[] body(GrugUseBlockPacket packet) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            packet.write(out);
        }
        return bytes.toByteArray();
    }
}
