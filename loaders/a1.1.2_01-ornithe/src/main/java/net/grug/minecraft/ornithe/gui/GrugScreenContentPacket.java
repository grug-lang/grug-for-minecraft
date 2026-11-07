package net.grug.minecraft.ornithe.gui;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.GrugAlphaMenu;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The slot contents and the carried stack of a server-held grug menu on Alpha (#1098).
 *
 * <p>The server sends one of these after it opens a menu and after every click it applies, because
 * Alpha has no container packets: the client's copy of the block entity is not told what a click
 * did, and its own screen must never apply a click itself. One entry per slot, in {@code
 * GrugAlphaMenu.build}'s order, so an index means the same slot on both sides, plus the cursor,
 * which on Alpha is not a slot.
 *
 * <p>Only a server sends it and only a client applies it; both ends register it under {@link #ID}
 * so Alpha's registry never sees an unknown id.
 */
@GrugGenerated(
        "the game's packet type: only the dispatch-only multiplayer job opens a GUI on a server")
public class GrugScreenContentPacket extends Packet {
    /** The companion of {@link GrugScreenOpenPacket#ID}. */
    public static final int ID = 153;

    /**
     * The most slots a menu can name. A layout is a few grids of nine plus the player's 36, and a
     * corrupt count is refused rather than turned into an array of that size.
     */
    private static final int MAX_SLOTS = 1000;

    /** The client half, which only the a1 client entrypoint installs (#1098). */
    public interface Handler {
        void contents(int windowId, ItemStack cursor, ItemStack[] slots);
    }

    public static volatile Handler clientHandler = null;

    private int windowId;
    private ItemStack cursor = null;
    private ItemStack[] slots = new ItemStack[0];

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugScreenContentPacket() {}

    private GrugScreenContentPacket(int windowId, ItemStack cursor, ItemStack[] slots) {
        this.windowId = windowId;
        this.cursor = cursor;
        this.slots = slots;
    }

    /**
     * Everything the menu holds, for the client that draws it. The stacks are copied, because the
     * connection sends a packet on its writer thread later: a click that changed a slot in the
     * meantime would otherwise change this packet after it was sent.
     */
    public static GrugScreenContentPacket of(GrugAlphaMenu menu) {
        ItemStack[] contents = new ItemStack[menu.slots.size()];
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = GrugAlphaMenu.itemOf(menu.slots.get(i));
            contents[i] = stack == null ? null : stack.copy();
        }
        return new GrugScreenContentPacket(
                menu.windowId, menu.cursor == null ? null : menu.cursor.copy(), contents);
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        windowId = in.readInt();
        cursor = readStack(in);
        int count = in.readInt();
        if (count < 0 || count > MAX_SLOTS) {
            throw new IOException("A GUI content packet names " + count + " slots");
        }
        slots = new ItemStack[count];
        for (int i = 0; i < count; i++) {
            slots[i] = readStack(in);
        }
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(windowId);
        writeStack(out, cursor);
        out.writeInt(slots.length);
        for (ItemStack stack : slots) {
            writeStack(out, stack);
        }
    }

    @Override
    public void handle(PacketHandler handler) {
        Handler target = clientHandler;
        if (target != null) {
            target.contents(windowId, cursor, slots);
        }
    }

    /**
     * Writes a stack as its three public fields, which both this generation's client and its server
     * have; they hold no other data.
     */
    static void writeStack(DataOutputStream out, ItemStack stack) throws IOException {
        out.writeBoolean(stack != null);
        if (stack != null) {
            out.writeInt(stack.id);
            out.writeInt(stack.size);
            out.writeInt(stack.metadata);
        }
    }

    static ItemStack readStack(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        return new ItemStack(in.readInt(), in.readInt(), in.readInt());
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
