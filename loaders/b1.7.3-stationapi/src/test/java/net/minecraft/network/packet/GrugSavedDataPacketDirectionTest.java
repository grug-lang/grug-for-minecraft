package net.minecraft.network.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.grug.minecraft.core.GrugPacketDirection;
import net.grug.minecraft.stationapi.block.entity.GrugSavedDataPacket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * What the game's own packet table makes of the saved-data packet's direction (#1097).
 *
 * <p>The table keeps a set per direction and {@code read(input, server)} checks the one the reader
 * needs, so a packet registered server to client only is refused when a server reads it. That is
 * what stops a client spoofing saved data into the world a server simulates, and it is a property
 * of the game's table, which exists only with the game loaded: a unit test in the game's own
 * package is what pins it.
 *
 * <p>{@code register} is private to the game's package, which is why this test lives in it, and the
 * packet it registers is grug's own, with the direction {@code InitListener} registers it in.
 */
class GrugSavedDataPacketDirectionTest {

    @Test
    void aServerRefusesASavedDataPacketAClientSends() throws IOException {
        GrugPacketDirection direction = GrugPacketDirection.SERVER_TO_CLIENT;
        Packet.register(
                GrugSavedDataPacket.ID,
                direction.serverToClient(),
                direction.clientToServer(),
                GrugSavedDataPacket.class);

        // The id and a body, as a server writes them to the client that holds the copy.
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        Packet.write(new GrugSavedDataPacket(), new DataOutputStream(written));
        byte[] bytes = written.toByteArray();

        // A server reading them is the spoof this refuses: the table answers before the packet is
        // made, so nothing of it reaches the world the server simulates.
        IOException refused =
                assertThrows(
                        IOException.class,
                        () ->
                                Packet.read(
                                        new DataInputStream(new ByteArrayInputStream(bytes)),
                                        true));
        assertEquals(
                "Bad packet id " + GrugSavedDataPacket.ID,
                refused.getMessage(),
                "a server must not read a packet this direction leaves to the client");

        // The client is the end the packet is for, so the table lets it through and the packet is
        // made from the same bytes.
        assertInstanceOf(
                GrugSavedDataPacket.class,
                Packet.read(new DataInputStream(new ByteArrayInputStream(bytes)), false),
                "the client must accept the id, which is the end the packet is for");
    }
}
