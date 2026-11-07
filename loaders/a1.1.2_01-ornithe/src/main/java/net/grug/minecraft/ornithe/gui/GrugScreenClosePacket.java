package net.grug.minecraft.ornithe.gui;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.ServerOrnitheAdapter;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The client closed a server-held grug menu on Alpha (#1098), which this generation otherwise never
 * tells the server: its container screen closes by drawing nothing, with no packet back.
 *
 * <p>Without it the server would keep the menu and its carried stack after the player pressed
 * Escape, and a carried stack would reappear on the next screen instead of being dropped where the
 * player closed the first one. It names the window it closed, so a close that arrives after {@code
 * GUI.open} replaced that menu (the client's close of the screen the open replaced) is ignored
 * rather than closing the new one.
 *
 * <p>Both ends register it under {@link #ID}, because Alpha's registry refuses an id the receiver
 * does not know, but only a client sends it and only a server applies it.
 */
@GrugGenerated(
        "the game's packet type: only the dispatch-only multiplayer job opens a GUI on a server")
public class GrugScreenClosePacket extends Packet {
    /** The companion of {@link GrugScreenOpenPacket#ID}. */
    public static final int ID = 155;

    private int windowId;

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugScreenClosePacket() {}

    public GrugScreenClosePacket(int windowId) {
        this.windowId = windowId;
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        windowId = in.readInt();
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(windowId);
    }

    @Override
    public void handle(PacketHandler handler) {
        if (GrugCore.getAdapter() == null
                || GrugCore.getAdapter().getSide() != GrugSide.DEDICATED_SERVER) {
            return;
        }
        ServerOrnitheAdapter.closeFromClient(handler, windowId);
    }

    @Override
    public int getSize() {
        return 4;
    }
}
