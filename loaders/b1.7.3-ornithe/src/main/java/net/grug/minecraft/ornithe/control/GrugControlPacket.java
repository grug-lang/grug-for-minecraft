package net.grug.minecraft.ornithe.control;

import net.grug.minecraft.core.GrugControlWire;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The one packet of the test-control channel on Beta (#1040, option A): an int length and the bytes
 * of one {@code GrugControlWire} message. Beta has no length prefix on the wire and no cap on a
 * packet, so a message of 20000 bytes is no problem; the limit here is only a sanity bound.
 *
 * <p>Both ends register it under {@link #ID} (see {@code PacketRegisterMixin}), because a packet id
 * the receiver does not know closes the connection with "Bad packet id".
 *
 * <p>{@link #read} runs on the connection's reader thread, which is the one thread still alive
 * while the server's game thread is frozen waiting for a client's answer, so an answer is handed to
 * the waiting capture from there, and a give is queued for the game thread that drains it. Anything
 * else is handled by {@link #handle}, on the game thread, as any packet is.
 */
@GrugGenerated(
        "the game's packet type: its registration and its hand-off to the game's handlers need a"
                + " running game; the framing it calls is unit-tested in GrugControlFrameTest")
public class GrugControlPacket extends Packet {
    /** Vanilla uses 0 to 6, 10 to 34, 50 to 53, 59, 100 to 108, 130, 255 and more; 150 is free. */
    public static final int ID = 150;

    public byte[] bytes = new byte[0];

    /** True once {@link #read} has handed the message on, so {@link #handle} must not. */
    public boolean consumed = false;

    public GrugControlPacket() {}

    public GrugControlPacket(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        bytes = GrugControlWire.readFrame(in);
        consumed = GrugControlBeta.onRead(bytes);
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        GrugControlWire.writeFrame(out, bytes);
    }

    @Override
    public void handle(PacketHandler handler) {
        if (!consumed) {
            GrugControlBeta.onHandle(handler, bytes);
        }
    }

    @Override
    public int getSize() {
        return 4 + bytes.length;
    }
}
