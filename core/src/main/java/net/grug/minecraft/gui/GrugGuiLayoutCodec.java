package net.grug.minecraft.gui;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The layout of a grug GUI in the bytes of a packet: the texture, the player inventory's placement,
 * the crafting grids, the crafting results and the labels.
 *
 * <p>A server builds a GUI's container from the layout a mod filled in, and the joining client
 * cannot rebuild that layout from its own copy of a {@code GrugGuiBuilder}, so the layout crosses
 * the process boundary and both sides build their slots from the same records. Every loader that
 * opens a GUI on a dedicated server writes it with this class, so their slot order cannot drift
 * apart.
 *
 * <p>The game's own string codec is not used, so a label is not capped at its length. A length is
 * bounded here instead, so a corrupt stream cannot ask for a huge allocation, and so are the record
 * counts, so it cannot make the reader loop until the stream ends.
 */
public final class GrugGuiLayoutCodec {
    /** The most bytes one label or texture path may hold. */
    public static final int MAX_TEXT = 1 << 20;

    /**
     * The most grids, results or labels one layout may hold. A layout is a handful of each, and a
     * corrupt or hostile stream must not be able to make a client loop until the stream ends.
     */
    public static final int MAX_ENTRIES = 64;

    private GrugGuiLayoutCodec() {}

    /** Writes {@code layout} field for field, in the order {@link #read} reads it. */
    public static void write(DataOutput out, GrugGuiBuilder layout) throws IOException {
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

    /** Reads what {@link #write} wrote, refusing a text length no layout can hold. */
    public static GrugGuiBuilder read(DataInput in) throws IOException {
        GrugGuiBuilder layout = new GrugGuiBuilder(readText(in));
        layout.hasPlayerInventory = in.readBoolean();
        layout.playerInvX = in.readInt();
        layout.playerInvY = in.readInt();
        layout.hotbarX = in.readInt();
        layout.hotbarY = in.readInt();

        int grids = readCount(in, "grids");
        for (int i = 0; i < grids; i++) {
            layout.craftingGrids.add(
                    new GrugGuiBuilder.CraftingGridDef(in.readInt(), in.readInt(), in.readInt()));
        }

        int results = readCount(in, "results");
        for (int i = 0; i < results; i++) {
            layout.craftingResults.add(
                    new GrugGuiBuilder.CraftingResultDef(in.readInt(), in.readInt()));
        }

        int texts = readCount(in, "texts");
        for (int i = 0; i < texts; i++) {
            layout.texts.add(
                    new GrugGuiBuilder.TextDef(
                            readText(in), in.readInt(), in.readInt(), in.readInt()));
        }
        return layout;
    }

    /** Reads a record count, refusing one no layout can hold. */
    private static int readCount(DataInput in, String what) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("a GUI layout names " + count + " " + what);
        }
        return count;
    }

    private static void writeText(DataOutput out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT) {
            throw new IOException("a GUI layout's text is " + length + " bytes long");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
