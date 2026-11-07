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
 * A click on a server-held grug menu on Alpha (#1098): which menu, which slot, which button.
 *
 * <p>Alpha's own container screen applies a click to the client's copy of the inventory, because
 * this generation has no click packet and no menu the server owns. Now that a server builds the
 * block entity's own menu, its screen sends this instead, and the server applies the click to its
 * own inventory and answers with {@link GrugScreenContentPacket}. A click outside the panel names
 * {@link #OUTSIDE}, which the server applies to the carried stack as the game's own screen does.
 *
 * <p>Both ends register it under {@link #ID}, because Alpha's registry refuses an id the receiver
 * does not know, but only a client sends it and only a server applies it.
 */
@GrugGenerated(
        "the game's packet type: only the dispatch-only multiplayer job opens a GUI on a server")
public class GrugScreenClickPacket extends Packet {
    /** The companion of {@link GrugScreenOpenPacket#ID}. */
    public static final int ID = 154;

    /**
     * The slot index of a click that named no slot, as the game's screen drops the cursor there.
     */
    public static final int OUTSIDE = -1;

    private int windowId;
    private int slot = OUTSIDE;
    private int button;

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugScreenClickPacket() {}

    public GrugScreenClickPacket(int windowId, int slot, int button) {
        this.windowId = windowId;
        this.slot = slot;
        this.button = button;
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        windowId = in.readInt();
        slot = in.readInt();
        button = in.readInt();
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(windowId);
        out.writeInt(slot);
        out.writeInt(button);
    }

    @Override
    public void handle(PacketHandler handler) {
        if (GrugCore.getAdapter() == null
                || GrugCore.getAdapter().getSide() != GrugSide.DEDICATED_SERVER) {
            return;
        }
        ServerOrnitheAdapter.clickFromClient(handler, windowId, slot, button);
    }

    @Override
    public int getSize() {
        return 12;
    }
}
