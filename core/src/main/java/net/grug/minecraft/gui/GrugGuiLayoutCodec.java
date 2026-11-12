package net.grug.minecraft.gui;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
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
 * counts, so it cannot make the reader loop until the stream ends. A layout holding more records or
 * a longer text than the reader accepts is refused when it is written too, because the alternative
 * is a client that refuses the packet and loses the connection the server sent it over.
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

    /**
     * The bytes {@code text} takes in a layout, which is the measure {@link #MAX_TEXT} bounds. A
     * caller that adds a label asks this before it adds one, so the layout a script builds is one
     * the reader accepts and the call that would not fit is the thing that fails.
     */
    public static int textBytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Writes {@code layout} field for field, in the order {@link #read} reads it. */
    public static void write(DataOutput out, GrugGuiBuilder layout) throws IOException {
        writeText(out, layout.texturePath != null ? layout.texturePath : "");
        out.writeBoolean(layout.hasPlayerInventory);
        out.writeInt(layout.playerInvX);
        out.writeInt(layout.playerInvY);
        out.writeInt(layout.hotbarX);
        out.writeInt(layout.hotbarY);

        writeCount(out, layout.craftingGrids.size(), "grids");
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            out.writeInt(grid.startSlot());
            out.writeInt(grid.x());
            out.writeInt(grid.y());
        }

        writeCount(out, layout.craftingResults.size(), "results");
        for (GrugGuiBuilder.CraftingResultDef result : layout.craftingResults) {
            out.writeInt(result.x());
            out.writeInt(result.y());
        }

        writeCount(out, layout.texts.size(), "texts");
        for (GrugGuiBuilder.TextDef text : layout.texts) {
            writeText(out, text.text());
            out.writeInt(text.x());
            out.writeInt(text.y());
            out.writeInt(text.color());
        }
    }

    /** Reads what {@link #write} wrote, refusing a text length no layout can hold. */
    public static GrugGuiBuilder read(DataInputStream in) throws IOException {
        return read(in, true);
    }

    /**
     * Consumes what {@link #write} wrote and throws it away, for the side of a connection that only
     * has to stay in step with the stream.
     *
     * <p>The game reads a packet's body straight from the connection, with no length in front of
     * it, so the side that must not decode a layout still has to read exactly as much as a decoder
     * would. It is walked here rather than built, so a body a client sends a server is not turned
     * into a layout of its own.
     */
    public static void skip(DataInputStream in) throws IOException {
        read(in, false);
    }

    /**
     * Reads the layout field for field, or with {@code keep} false only walks the same fields and
     * drops what it read. One implementation, so the fields a skipped layout steps over cannot
     * drift from the fields a decoded one reads.
     */
    private static GrugGuiBuilder read(DataInputStream in, boolean keep) throws IOException {
        GrugGuiBuilder layout = new GrugGuiBuilder(readText(in, keep));
        layout.hasPlayerInventory = in.readBoolean();
        layout.playerInvX = in.readInt();
        layout.playerInvY = in.readInt();
        layout.hotbarX = in.readInt();
        layout.hotbarY = in.readInt();

        int grids = readCount(in, "grids");
        for (int i = 0; i < grids; i++) {
            int startSlot = in.readInt();
            int x = in.readInt();
            int y = in.readInt();
            if (keep) {
                layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(startSlot, x, y));
            }
        }

        int results = readCount(in, "results");
        for (int i = 0; i < results; i++) {
            int x = in.readInt();
            int y = in.readInt();
            if (keep) {
                layout.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(x, y));
            }
        }

        int texts = readCount(in, "texts");
        for (int i = 0; i < texts; i++) {
            String text = readText(in, keep);
            int x = in.readInt();
            int y = in.readInt();
            int color = in.readInt();
            if (keep) {
                layout.texts.add(new GrugGuiBuilder.TextDef(text, x, y, color));
            }
        }
        return layout;
    }

    /** Writes a record count, refusing one no layout could have read back. */
    private static void writeCount(DataOutput out, int count, String what) throws IOException {
        if (count > MAX_ENTRIES) {
            throw new IOException("a GUI layout names " + count + " " + what);
        }
        out.writeInt(count);
    }

    /** Reads a record count, refusing one no layout can hold. */
    private static int readCount(DataInput in, String what) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("a GUI layout names " + count + " " + what);
        }
        return count;
    }

    /**
     * Writes a text, refusing one longer than {@link #MAX_TEXT}, which is the length the reader
     * refuses. A text no reader accepts is not written, so no caller can send a client a layout it
     * would refuse and lose the connection over.
     */
    private static void writeText(DataOutput out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) {
            throw new IOException("a GUI layout's text is " + bytes.length + " bytes long");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Reads a text, or with {@code keep} false skips its bytes and answers an empty one. A server
     * that only consumes a client's layout reads no text at all, so the most a body can cost it is
     * the buffer this copies into, never a text of the largest length it declares.
     */
    private static String readText(DataInputStream in, boolean keep) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT) {
            throw new IOException("a GUI layout's text is " + length + " bytes long");
        }
        if (!keep) {
            skipFully(in, length);
            return "";
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Reads past {@code length} bytes, in pieces so no text's size becomes one allocation. */
    private static void skipFully(DataInputStream in, int length) throws IOException {
        byte[] scratch = new byte[Math.min(length, 1 << 13)];
        int left = length;
        while (left > 0) {
            int read = in.read(scratch, 0, Math.min(scratch.length, left));
            if (read < 0) {
                throw new EOFException(
                        "a GUI layout's text is shorter than its " + length + " bytes");
            }
            left -= read;
        }
    }
}
