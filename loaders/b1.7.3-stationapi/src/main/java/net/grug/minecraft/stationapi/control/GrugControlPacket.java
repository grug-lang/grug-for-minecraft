package net.grug.minecraft.stationapi.control;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.network.NetworkHandler;
import net.minecraft.network.packet.Packet;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The one packet of the test-control channel on StationAPI (#1040, option A): an int length and the
 * bytes of one {@code GrugControlWire} message. This loader has no length prefix on the wire and no
 * cap on a packet, so a message of 20000 bytes is no problem; the limit here is only a sanity
 * bound.
 *
 * <p>Both ends register it under {@link #ID} (see {@code PacketRegisterMixin}), because a packet id
 * the receiver does not know closes the connection with "Bad packet id".
 *
 * <p>{@link #read} runs on the connection's reader thread, which is the one thread still alive
 * while the server's game thread is frozen waiting for a client's answer, so an answer is handed to
 * the waiting capture from there. Everything else is handled by {@link #apply}, on the game thread,
 * as any packet is.
 */
@GrugGenerated(
        "the StationAPI channel glue of #1040: no test the coverage gate counts can reach it, since"
                + " only the multiplayer job runs it")
public class GrugControlPacket extends Packet {
    /** Vanilla uses 0 to 6, 10 to 34, 50 to 53, 59 and 255. */
    public static final int ID = 100;

    private static final int MAX_BYTES = 1 << 20;

    public byte[] bytes = new byte[0];

    /** True once {@link #read} has handed the message on, so {@link #apply} must not. */
    public boolean consumed = false;

    public GrugControlPacket() {}

    public GrugControlPacket(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public void read(DataInputStream in) {
        // The mapped signature declares no IOException; the connection's reader catches any
        // Exception and closes the connection, so a wrapped one ends it the same way.
        try {
            int length = in.readInt();
            if (length < 0 || length > MAX_BYTES) {
                throw new IOException("A control message of " + length + " bytes is not allowed");
            }
            bytes = new byte[length];
            in.readFully(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        consumed = GrugControlStation.onRead(bytes);
    }

    @Override
    public void write(DataOutputStream out) {
        try {
            out.writeInt(bytes.length);
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void apply(NetworkHandler handler) {
        if (!consumed) {
            GrugControlStation.onHandle(handler, bytes);
        }
    }

    @Override
    public int size() {
        return 4 + bytes.length;
    }
}
