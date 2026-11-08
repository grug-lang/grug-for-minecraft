package net.grug.minecraft.ornithe.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.gui.GrugGuiLayoutCodec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Which side of an Alpha connection decodes a screen's layout (#1216).
 *
 * <p>Alpha sets no direction on a packet's registration, so a server has the same id registered as
 * a client and would decode whatever a client sends it. It must consume the body instead, without a
 * layout of its own, and it must consume it exactly: the game reads a packet straight off the
 * connection with no length in front of it, so a byte left behind is read as the next packet's id.
 *
 * <p>A joined client's screen only the dispatch-only multiplayer job reaches, so the side is passed
 * in here rather than read from the running loader.
 */
class GrugScreenOpenPacketTest {

    /** A value the server writes after the layout, so a reader proves how far it read. */
    private static final int MARKER = 0x5eed;

    @Test
    void aClientDecodesTheLayoutAServerSends() throws IOException {
        GrugGuiBuilder layout = largestLayout();
        DataInputStream in = body(layout);

        GrugGuiBuilder read = new GrugScreenOpenPacket().read(in, GrugSide.CLIENT);

        assertEquals(layout.texturePath, read.texturePath);
        assertEquals(layout.craftingGrids, read.craftingGrids);
        assertEquals(layout.craftingResults, read.craftingResults);
        assertEquals(layout.texts, read.texts);
        assertEquals(
                MARKER,
                in.readInt(),
                "a decoded layout has to step over exactly the bytes it wrote, or the packet after"
                        + " it is lost");
    }

    @Test
    void aServerConsumesAClientsLayoutWithoutBuildingIt() throws IOException {
        GrugGuiBuilder layout = largestLayout();
        DataInputStream in = body(layout);

        GrugGuiBuilder read = new GrugScreenOpenPacket().read(in, GrugSide.DEDICATED_SERVER);

        assertNull(read, "a server has to throw a layout a client sends it away, not build one");
        assertEquals(
                MARKER,
                in.readInt(),
                "a consumed layout has to step over exactly the bytes it wrote, or the packet"
                        + " after it is lost");
    }

    @Test
    void theSideThatHasNoLoaderAdapterYetConsumesALayoutToo() throws IOException {
        GrugGuiBuilder layout = largestLayout();
        DataInputStream in = body(layout);

        GrugGuiBuilder read = new GrugScreenOpenPacket().read(in, null);

        assertNull(
                read,
                "without an adapter there is no client to hold a screen, so nothing is built");
        assertEquals(MARKER, in.readInt());
    }

    /** The most records a layout may hold, which is what a client's reader accepts. */
    private static GrugGuiBuilder largestLayout() {
        GrugGuiBuilder layout = new GrugGuiBuilder("grug:textures/gui/largest.png");
        layout.hasPlayerInventory = true;
        layout.playerInvX = 8;
        layout.playerInvY = 84;
        layout.hotbarX = 8;
        layout.hotbarY = 142;
        layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 30, 17));
        layout.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(124, 35));
        for (int i = 0; i < GrugGuiLayoutCodec.MAX_ENTRIES; i++) {
            layout.texts.add(new GrugGuiBuilder.TextDef("text " + i, 8, 6 + i, 0x404040));
        }
        return layout;
    }

    /** The body a server writes, then the marker, as one stream's bytes. */
    private static DataInputStream body(GrugGuiBuilder layout) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(7);
            out.writeInt(1);
            out.writeInt(2);
            out.writeInt(3);
            GrugGuiLayoutCodec.write(out, layout);
            out.writeInt(MARKER);
        }
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
