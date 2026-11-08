package net.grug.minecraft.ornithe.gui;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSide;
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
 * applies it. Alpha sets no direction on a registration either, so a server that receives one
 * replaces its direction check with its own: it consumes the body without decoding the layout,
 * where a decoded one is what a client could make it build.
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
        read(in, GrugCore.getAdapter() == null ? null : GrugCore.getAdapter().getSide());
    }

    /**
     * Reads the body for {@code side}, answering the layout it decoded, or null when this side only
     * has to consume one a server sent.
     *
     * <p>Alpha has no direction on a packet's registration, so the side that only receives a copy
     * has to refuse it here. The body cannot simply be left in the stream: the game reads a packet
     * straight off the connection with no length in front of it, so the next packet's id would be
     * read out of this one's payload. It is skipped instead, which keeps the stream in step without
     * building a layout the server would throw away.
     *
     * <p>The overload is what lets a unit test pin both directions: a joined client's screen is
     * only reachable from the dispatch-only multiplayer job (#1216).
     */
    GrugGuiBuilder read(DataInputStream in, GrugSide side) throws IOException {
        windowId = in.readInt();
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        if (side == null || side.isDedicatedServer()) {
            GrugGuiLayoutCodec.skip(in);
            return null;
        }
        layout = GrugGuiLayoutCodec.read(in);
        return layout;
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
     * and only consumes the body of one a client sends it, so this does nothing there.
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
