package net.grug.minecraft.forge125.control;

import net.grug.minecraft.core.GrugControlWire;
import net.grug.minecraft.core.GrugPacketDirection;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.NetHandler;
import net.minecraft.src.Packet;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The one packet of the test-control channel on 1.2.5 (#1040, option A): an int length and the
 * bytes of one {@code GrugControlWire} message. It is a packet of its own and not a custom payload
 * (250), because a payload is handled on the game thread, which is the thread frozen while the
 * server waits for the client's answer: this one's {@link #readPacketData} runs on the connection's
 * reader thread, which stays alive.
 *
 * <p>Both ends register it under {@link #ID} ({@link #register}), because a packet id the receiver
 * does not know closes the connection.
 */
@GrugGenerated(
        "the game's packet type: its registration and its hand-off to the game's handlers need a"
                + " running game; the framing it calls is unit-tested in GrugControlFrameTest")
public class GrugControlPacket extends Packet {
    /** Vanilla and Forge use 0 to 26, 28 to 43, 50 to 54, 60, 61, 70, 71, 100 to 108 and up. */
    public static final int ID = 150;

    /** The half that handles messages, set by whichever side this process is. */
    public static volatile GrugControlRouter router = null;

    public byte[] bytes = new byte[0];

    /** True once {@link #readPacketData} has handed the message on, so the game thread must not. */
    public boolean consumed = false;

    public GrugControlPacket() {}

    public GrugControlPacket(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * Registers the packet in the game's packet table. See {@link GrugPacketRegister#register},
     * which every packet of grug's own on this loader uses.
     */
    public static void register() {
        GrugPacketRegister.register(ID, GrugControlPacket.class, GrugPacketDirection.BOTH_WAYS);
    }

    @Override
    public void readPacketData(DataInputStream in) throws IOException {
        bytes = GrugControlWire.readFrame(in);
        GrugControlRouter target = router;
        consumed = target != null && target.onRead(Thread.currentThread(), bytes);
    }

    @Override
    public void writePacketData(DataOutputStream out) throws IOException {
        GrugControlWire.writeFrame(out, bytes);
    }

    @Override
    public void processPacket(NetHandler handler) {
        GrugControlRouter target = router;
        if (!consumed && target != null) {
            target.onHandle(handler, bytes);
        }
    }

    @Override
    public int getPacketSize() {
        return 4 + bytes.length;
    }
}
