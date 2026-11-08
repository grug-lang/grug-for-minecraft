package net.grug.minecraft.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * The bytes a server sends a client so both build the same GUI slots (#1098). A round trip has to
 * keep every field, because a dropped one changes where a click lands, and a corrupt length has to
 * be refused rather than allocate it.
 */
class GrugGuiLayoutCodecTest {
    /** A value after a written layout, so a reader that steps over it proves how far it read. */
    private static final int MARKER = 0x5eed;

    @Test
    void roundTripsEveryField() throws IOException {
        GrugGuiBuilder layout =
                new GrugGuiBuilder("coverage/assets/grug/textures/gui/crafting.png");
        layout.hasPlayerInventory = true;
        layout.playerInvX = 8;
        layout.playerInvY = 84;
        layout.hotbarX = 8;
        layout.hotbarY = 142;
        layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 30, 17));
        layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(9, 30, 35));
        layout.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(124, 35));
        layout.texts.add(new GrugGuiBuilder.TextDef("Storage", 8, 6, 0x404040));
        layout.texts.add(new GrugGuiBuilder.TextDef("\u00e9t\u00e9", 8, 20, 0xff0000));

        GrugGuiBuilder read = roundTrip(layout);

        assertEquals(layout.texturePath, read.texturePath);
        assertTrue(read.hasPlayerInventory);
        assertEquals(layout.playerInvX, read.playerInvX);
        assertEquals(layout.playerInvY, read.playerInvY);
        assertEquals(layout.hotbarX, read.hotbarX);
        assertEquals(layout.hotbarY, read.hotbarY);
        assertEquals(layout.craftingGrids, read.craftingGrids);
        assertEquals(layout.craftingResults, read.craftingResults);
        assertEquals(layout.texts, read.texts);
    }

    @Test
    void roundTripsALayoutWithNoRecordsAndNoPlayerInventory() throws IOException {
        GrugGuiBuilder layout = new GrugGuiBuilder("grug:textures/gui/empty.png");

        GrugGuiBuilder read = roundTrip(layout);

        assertEquals(layout.texturePath, read.texturePath);
        assertFalse(read.hasPlayerInventory);
        assertTrue(read.craftingGrids.isEmpty());
        assertTrue(read.craftingResults.isEmpty());
        assertTrue(read.texts.isEmpty());
    }

    @Test
    void writesNoTexturePathAsEmptyText() throws IOException {
        GrugGuiBuilder read = roundTrip(new GrugGuiBuilder(null));

        assertEquals("", read.texturePath);
    }

    @Test
    void refusesANegativeTextLength() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(-1);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThrows(IOException.class, () -> read(bytes.toByteArray()));
    }

    @Test
    void refusesAnOversizedTextLength() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(GrugGuiLayoutCodec.MAX_TEXT + 1);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThrows(IOException.class, () -> read(bytes.toByteArray()));
    }

    @Test
    void refusesATextShorterThanItsLength() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(4);
            out.writeByte('a');
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThrows(EOFException.class, () -> read(bytes.toByteArray()));
    }

    @Test
    void refusesTooManyGrids() {
        assertThrows(
                IOException.class,
                () -> read(layoutHeader(GrugGuiLayoutCodec.MAX_ENTRIES + 1, 0, 0)));
    }

    @Test
    void refusesTooManyResults() {
        assertThrows(
                IOException.class,
                () -> read(layoutHeader(0, GrugGuiLayoutCodec.MAX_ENTRIES + 1, 0)));
    }

    @Test
    void refusesTooManyTexts() {
        assertThrows(
                IOException.class,
                () -> read(layoutHeader(0, 0, GrugGuiLayoutCodec.MAX_ENTRIES + 1)));
    }

    @Test
    void refusesANegativeRecordCount() {
        assertThrows(IOException.class, () -> read(layoutHeader(-1, 0, 0)));
    }

    @Test
    void refusesToWriteTooManyGrids() {
        GrugGuiBuilder layout = new GrugGuiBuilder("");
        for (int i = 0; i <= GrugGuiLayoutCodec.MAX_ENTRIES; i++) {
            layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 0, 0));
        }

        assertThrows(IOException.class, () -> write(layout));
    }

    @Test
    void refusesToWriteTooManyResults() {
        GrugGuiBuilder layout = new GrugGuiBuilder("");
        for (int i = 0; i <= GrugGuiLayoutCodec.MAX_ENTRIES; i++) {
            layout.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(0, 0));
        }

        assertThrows(IOException.class, () -> write(layout));
    }

    @Test
    void refusesToWriteTooManyTexts() {
        GrugGuiBuilder layout = new GrugGuiBuilder("");
        for (int i = 0; i <= GrugGuiLayoutCodec.MAX_ENTRIES; i++) {
            layout.texts.add(new GrugGuiBuilder.TextDef("text", 0, 0, 0));
        }

        assertThrows(IOException.class, () -> write(layout));
    }

    @Test
    void writesTheMostRecordsTheReaderAccepts() throws IOException {
        GrugGuiBuilder layout = new GrugGuiBuilder("");
        for (int i = 0; i < GrugGuiLayoutCodec.MAX_ENTRIES; i++) {
            layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(i, i, i));
            layout.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(i, i));
            layout.texts.add(new GrugGuiBuilder.TextDef("text " + i, i, i, i));
        }

        GrugGuiBuilder read = roundTrip(layout);

        assertEquals(layout.craftingGrids, read.craftingGrids);
        assertEquals(layout.craftingResults, read.craftingResults);
        assertEquals(layout.texts, read.texts);
    }

    @Test
    void skippingALayoutConsumesExactlyWhatWritingItWrote() throws IOException {
        GrugGuiBuilder layout =
                new GrugGuiBuilder("coverage/assets/grug/textures/gui/crafting.png");
        layout.hasPlayerInventory = true;
        layout.playerInvX = 8;
        layout.playerInvY = 84;
        layout.hotbarX = 8;
        layout.hotbarY = 142;
        layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 30, 17));
        layout.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(124, 35));
        layout.texts.add(new GrugGuiBuilder.TextDef("Storage", 8, 6, 0x404040));
        layout.texts.add(new GrugGuiBuilder.TextDef("\u00e9t\u00e9", 8, 20, 0xff0000));

        DataInputStream in =
                new DataInputStream(new ByteArrayInputStream(writtenWithMarker(layout)));

        GrugGuiLayoutCodec.skip(in);

        assertEquals(
                MARKER,
                in.readInt(),
                "a skipped layout has to step over exactly the bytes it wrote, or the reader of a"
                        + " stream with no packet lengths loses the packets after it");
    }

    @Test
    void refusesToSkipAnOversizedTextLength() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(GrugGuiLayoutCodec.MAX_TEXT + 1);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThrows(IOException.class, () -> skip(bytes.toByteArray()));
    }

    @Test
    void refusesToSkipATextShorterThanItsLength() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(4);
            out.writeByte('a');
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThrows(EOFException.class, () -> skip(bytes.toByteArray()));
    }

    /**
     * The bytes before the first record: an empty texture, no player inventory, zero placement, and
     * the three counts a stream declares.
     */
    private static byte[] layoutHeader(int grids, int results, int texts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0);
            out.writeBoolean(false);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(grids);
            out.writeInt(results);
            out.writeInt(texts);
        }
        return bytes.toByteArray();
    }

    private static GrugGuiBuilder roundTrip(GrugGuiBuilder layout) throws IOException {
        return read(write(layout));
    }

    private static byte[] write(GrugGuiBuilder layout) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            GrugGuiLayoutCodec.write(out, layout);
        }
        return bytes.toByteArray();
    }

    /** A written layout and the marker after it, as one stream's bytes. */
    private static byte[] writtenWithMarker(GrugGuiBuilder layout) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            GrugGuiLayoutCodec.write(out, layout);
            out.writeInt(MARKER);
        }
        return bytes.toByteArray();
    }

    private static GrugGuiBuilder read(byte[] bytes) throws IOException {
        return GrugGuiLayoutCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static void skip(byte[] bytes) throws IOException {
        GrugGuiLayoutCodec.skip(new DataInputStream(new ByteArrayInputStream(bytes)));
    }
}
