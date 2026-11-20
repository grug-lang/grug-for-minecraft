package net.grug.minecraft.stationapi.control;

import net.grug.minecraft.core.GrugControlWire;
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
 * the waiting capture from there, and a give is queued for the game thread that drains it. On this
 * loader {@link #apply} is also read on the reader thread, so anything else is queued to the game
 * thread there and drained from {@code MinecraftMixin#onClientTick}, because requests that touch
 * the GL context must run on the thread that owns the frame (#1465).
 */
@GrugGenerated(
        "the game's packet type: its registration and its hand-off to the game's handlers need a"
                + " running game; the framing it calls is unit-tested in GrugControlFrameTest")
public class GrugControlPacket extends Packet {
    /** Vanilla uses 0 to 6, 10 to 34, 50 to 53, 59, 100 to 108, 130, 255 and more; 150 is free. */
    public static final int ID = 150;

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
            bytes = GrugControlWire.readFrame(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        consumed = GrugControlStation.onRead(bytes);
    }

    @Override
    public void write(DataOutputStream out) {
        try {
            GrugControlWire.writeFrame(out, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void apply(NetworkHandler handler) {
        if (!consumed) {
            // StationAPI delivers packets on the connection's reader thread, not the game thread,
            // so requests that touch the GL context (forced normalization) must wait for the tick
            // that drains them. The queue lives in core so the loader only has to drain it.
            net.grug.minecraft.core.GrugControlClient.queueMessage(bytes);
        }
    }

    @Override
    public int size() {
        return 4 + bytes.length;
    }
}
