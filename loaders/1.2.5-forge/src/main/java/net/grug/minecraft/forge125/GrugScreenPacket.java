package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugPacketDirection;
import net.grug.minecraft.forge125.control.GrugPacketRegister;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.src.NetHandler;
import net.minecraft.src.Packet;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Opens a grug GUI on the client of the player a server opened it for (#1098).
 *
 * <p>1.2.5's {@code GUI.open} used to build a screen and a container on the client alone, so a
 * click from it carried slot ids for a container the server did not have. The server now builds the
 * block entity's container itself and installs it as the player's open one, and this packet carries
 * what the client cannot work out for itself: the layout the container was built from, the window
 * id its click and content packets carry, and the block entity's position, so the client can find
 * its own copy of the inventory or fall back to a placeholder (#425).
 *
 * <p>Vanilla's own open packet cannot carry this: {@code Packet100OpenWindow} names a window type
 * the client switches on, and a grug GUI's slot positions, texture and labels differ per mod. The
 * layout is written with the same fields as 1.20.6's {@code GrugMenu.writeMenuData}, in one
 * serializer for both ends, so the two containers number their slots the same way.
 *
 * <p>Both ends register it under {@link #ID} ({@link #register}), because a packet id the receiver
 * does not know closes the connection, but only a server sends it and only a client applies it.
 */
@GrugGenerated(
        "the game's packet type: only the dispatch-only multiplayer job opens a GUI on a server")
public class GrugScreenPacket extends Packet {
    /**
     * Vanilla and Forge use 0 to 26, 28 to 43, 50 to 54, 60, 61, 70, 71 and 100 to 108; 150 is the
     * control channel and 151 a block entity's saved data, so 152 is the next free id.
     */
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

    /** The window id the server installed its container under, which the click packets carry. */
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

    /**
     * Registers the packet in the game's packet table. Both ends need it there, because an id the
     * receiver does not know closes the connection.
     */
    public static void register() {
        GrugPacketRegister.register(
                ID, GrugScreenPacket.class, GrugPacketDirection.SERVER_TO_CLIENT);
    }

    @Override
    public void readPacketData(DataInputStream in) throws IOException {
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        windowId = in.readInt();
        layout = readLayout(in);
    }

    @Override
    public void writePacketData(DataOutputStream out) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeInt(windowId);
        writeLayout(out, layout);
    }

    /**
     * Builds the client's screen from the layout, which is the client's own business and lives in
     * its half of this loader: a server installs no handler, so this does nothing there.
     */
    @Override
    public void processPacket(NetHandler handler) {
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
    public int getPacketSize() {
        ByteArrayOutputStream counted = new ByteArrayOutputStream();
        try {
            writePacketData(new DataOutputStream(counted));
        } catch (IOException e) {
            throw new IllegalStateException("cannot measure a screen packet", e);
        }
        return counted.size();
    }

    /**
     * The layout, field for field as 1.20.6's {@code GrugMenu.writeMenuData} writes it: the
     * texture, the player inventory's placement, the crafting grids, the crafting results and the
     * labels. The game's own string codec is not used, so a label is not capped at its length.
     */
    private static void writeLayout(DataOutputStream out, GrugGuiBuilder layout)
            throws IOException {
        writeText(out, layout.texturePath != null ? layout.texturePath : "");
        out.writeBoolean(layout.hasPlayerInventory);
        out.writeInt(layout.playerInvX);
        out.writeInt(layout.playerInvY);
        out.writeInt(layout.hotbarX);
        out.writeInt(layout.hotbarY);

        out.writeInt(layout.craftingGrids.size());
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            out.writeInt(grid.startSlot());
            out.writeInt(grid.x());
            out.writeInt(grid.y());
        }

        out.writeInt(layout.craftingResults.size());
        for (GrugGuiBuilder.CraftingResultDef result : layout.craftingResults) {
            out.writeInt(result.x());
            out.writeInt(result.y());
        }

        out.writeInt(layout.texts.size());
        for (GrugGuiBuilder.TextDef text : layout.texts) {
            writeText(out, text.text());
            out.writeInt(text.x());
            out.writeInt(text.y());
            out.writeInt(text.color());
        }
    }

    /** Reads what {@link #writeLayout} wrote. */
    private static GrugGuiBuilder readLayout(DataInputStream in) throws IOException {
        GrugGuiBuilder layout = new GrugGuiBuilder(readText(in));
        layout.hasPlayerInventory = in.readBoolean();
        layout.playerInvX = in.readInt();
        layout.playerInvY = in.readInt();
        layout.hotbarX = in.readInt();
        layout.hotbarY = in.readInt();

        int grids = in.readInt();
        for (int i = 0; i < grids; i++) {
            layout.craftingGrids.add(
                    new GrugGuiBuilder.CraftingGridDef(in.readInt(), in.readInt(), in.readInt()));
        }

        int results = in.readInt();
        for (int i = 0; i < results; i++) {
            layout.craftingResults.add(
                    new GrugGuiBuilder.CraftingResultDef(in.readInt(), in.readInt()));
        }

        int texts = in.readInt();
        for (int i = 0; i < texts; i++) {
            layout.texts.add(
                    new GrugGuiBuilder.TextDef(
                            readText(in), in.readInt(), in.readInt(), in.readInt()));
        }
        return layout;
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** The most bytes one label or texture path may hold, so a corrupt stream cannot allocate. */
    private static final int MAX_TEXT = 1 << 20;

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT) {
            throw new IOException("a screen packet's text is " + length + " bytes long");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
