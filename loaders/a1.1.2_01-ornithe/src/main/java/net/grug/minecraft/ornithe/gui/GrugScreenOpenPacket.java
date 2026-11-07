package net.grug.minecraft.ornithe.gui;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.gui.GrugGuiLayoutCodec;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Opens a grug GUI on the client of the player an Alpha server opened it for (#1098).
 *
 * <p>Alpha has no menu-open call and no menu packet: {@code PlayerEntity.openChestMenu} sends
 * nothing, and this generation has no container packets at all. So a server that builds the block
 * entity's own slot table sends this, which carries everything the joined client cannot work out
 * for itself: the layout the table was built from, the window id its click and content packets
 * carry, and the block entity's position, so the client draws over its own copy or a placeholder
 * (#425).
 *
 * <p>The layout goes through core's {@link GrugGuiLayoutCodec}, the same writer every
 * server-opening loader uses, and the slot table itself is {@code GrugAlphaMenu.build} on both
 * sides, so a click's slot index cannot mean two different slots.
 *
 * <p>Both ends register it under {@link #ID} (see {@code PacketRegisterMixin}), because Alpha's
 * registry refuses an id the receiver does not know, but only a server sends it and only a client
 * applies it.
 */
@GrugGenerated(
        "the game's packet type: only the dispatch-only multiplayer job opens a GUI on a server")
public class GrugScreenOpenPacket extends Packet {
    /**
     * 100 is the control channel and 151 saved data; 152 is free here because the Beta GUI packet
     * with that id lives in the b1 registry, a separate game. The Alpha GUI packets take 152 to
     * 155, one each for the open, the content, a click and a close.
     */
    public static final int ID = 152;

    /** The client half, which only the a1 client entrypoint installs (#1098). */
    public interface Handler {
        void openScreen(int x, int y, int z, int windowId, GrugGuiBuilder layout);
    }

    public static volatile Handler clientHandler = null;

    private int windowId;
    private int x;
    private int y;
    private int z;
    private GrugGuiBuilder layout = new GrugGuiBuilder("");

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugScreenOpenPacket() {}

    public GrugScreenOpenPacket(int windowId, int x, int y, int z, GrugGuiBuilder layout) {
        this.windowId = windowId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.layout = layout;
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        windowId = in.readInt();
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        layout = GrugGuiLayoutCodec.read(in);
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(windowId);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        GrugGuiLayoutCodec.write(out, layout);
    }

    /**
     * Hands the layout to the client that installed a handler. A dedicated server installs none,
     * and never receives this packet, so this does nothing there.
     */
    @Override
    public void handle(PacketHandler handler) {
        Handler target = clientHandler;
        if (target != null) {
            target.openScreen(x, y, z, windowId, layout);
        }
    }

    @Override
    public int getSize() {
        ByteArrayOutputStream counted = new ByteArrayOutputStream();
        try {
            write(new DataOutputStream(counted));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return counted.size();
    }
}
