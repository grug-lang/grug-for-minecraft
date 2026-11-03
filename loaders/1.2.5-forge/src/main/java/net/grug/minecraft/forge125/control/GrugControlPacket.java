package net.grug.minecraft.forge125.control;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.NetHandler;
import net.minecraft.src.Packet;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
        "the 1.2.5 channel glue of #1040: no test the coverage gate counts can reach it, since only"
                + " the multiplayer job runs it")
public class GrugControlPacket extends Packet {
    /** Vanilla and Forge use 0 to 26, 28 to 43, 50 to 54, 60, 61, 70, 71, 100 to 108 and up. */
    public static final int ID = 150;

    private static final int MAX_BYTES = 1 << 20;

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
     * Registers the packet in the game's packet table, found by its signature (a static method of
     * {@code Packet} taking an int, two booleans and a class) because the game's names are
     * obfuscated at run time and the method is not public.
     */
    public static void register() {
        for (Method method : Packet.class.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    && types.length == 4
                    && types[0] == int.class
                    && types[1] == boolean.class
                    && types[2] == boolean.class
                    && types[3] == Class.class) {
                try {
                    method.setAccessible(true);
                    method.invoke(null, ID, true, true, GrugControlPacket.class);
                    return;
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("cannot register the control packet", e);
                }
            }
        }
        throw new IllegalStateException("Packet has no method to register a packet with");
    }

    @Override
    public void readPacketData(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_BYTES) {
            throw new IOException("A control message of " + length + " bytes is not allowed");
        }
        bytes = new byte[length];
        in.readFully(bytes);
        GrugControlRouter target = router;
        consumed = target != null && target.onRead(Thread.currentThread(), bytes);
    }

    @Override
    public void writePacketData(DataOutputStream out) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
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
