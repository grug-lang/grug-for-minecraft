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
 * Opens a grug GUI on the client of the player a server opened it for (#1098).
 *
 * <p>Beta's {@code GUI.open} used to build a screen and a menu on the client alone, so a click from
 * it carried slot ids for a menu the server did not have. The server now builds the block entity's
 * own {@link GrugMenu}, installs it as the player's open one and sends this packet, which carries
 * what the client cannot work out for itself: the layout the menu was built from, the network id
 * its click and content packets carry, and the block entity's position, so the client can find its
 * own copy of the inventory or fall back to a placeholder (#425).
 *
 * <p>Vanilla's own open packet cannot carry this: {@code InventoryMenuOpenPacket} names a menu type
 * the client switches on, and a grug GUI's slot positions, texture and labels differ per mod. The
 * layout is written by {@link GrugGuiLayoutCodec}, which every server-opening loader shares, so the
 * two menus number their slots the same way.
 *
 * <p>Both ends register it under {@link #ID} (see {@code PacketRegisterMixin}), because a packet id
 * the receiver does not know closes the connection, but only a server sends it and only a client
 * applies it.
 */
@GrugGenerated(
        "the game's packet type: only the dispatch-only multiplayer job opens a GUI on a server")
public class GrugScreenPacket extends Packet {
    /** Vanilla uses 0 to 6, 10 to 34, 50 to 53, 59, 100 to 108, 130, 255 and more; 152 is free. */
    public static final int ID = 152;

    /**
     * The client half of the packet, which the client installs when it loads. A dedicated server
     * only sends, so it never sets one, and the packet does nothing there.
     */
    public interface Handler {
        void openScreen(int x, int y, int z, int windowId, GrugGuiBuilder layout);
    }

    public static volatile Handler clientHandler = null;

    private int x;
    private int y;
    private int z;

    /** The network id the server installed its menu under, which the click packets carry. */
    private int windowId;

    private GrugGuiBuilder layout = new GrugGuiBuilder("");

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugScreenPacket() {}

    /**
     * The GUI {@code layout} for the block entity at {@code (x, y, z)}, in window {@code windowId}.
     */
    public GrugScreenPacket(int windowId, int x, int y, int z, GrugGuiBuilder layout) {
        this.windowId = windowId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.layout = layout;
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        windowId = in.readInt();
        layout = GrugGuiLayoutCodec.read(in);
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeInt(windowId);
        GrugGuiLayoutCodec.write(out, layout);
    }

    /**
     * Builds the client's screen from the layout, which is the client's own business and lives in
     * its half of this loader: a server installs no handler, so this does nothing there.
     */
    @Override
    public void handle(PacketHandler handler) {
        Handler target = clientHandler;
        if (target != null) {
            target.openScreen(x, y, z, windowId, layout);
        }
    }

    /**
     * The size the game's network queue counts this packet as, worked out from what is written
     * because the layout is a variable amount of text and records.
     */
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
