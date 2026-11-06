package net.grug.minecraft.forge125.client;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.forge125.GrugScreenPacket;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntityPlayerSP;
import net.minecraft.src.GuiContainer;
import net.minecraft.src.IInventory;
import net.minecraft.src.InventoryBasic;
import net.minecraft.src.ModLoader;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Client-only: renders a grug GUI definition over a {@link GrugContainer}.
 *
 * <p>1.2.5 has no resource-pack layer the loader can hook, and its {@code RenderEngine} only reads
 * textures from the selected texture pack. A grug GUI texture lives next to the mod instead, so the
 * background is read from disk and uploaded directly.
 */
public class GrugScreen extends GuiContainer {
    private final GrugGuiBuilder layout;
    private boolean textureLoaded = false;
    private int textureId = -1;

    public GrugScreen(GrugContainer container, GrugGuiBuilder layout) {
        super(container);
        this.layout = layout;
        this.xSize = 176;
        this.ySize = 166;
    }

    /** Opens the screen for the local singleplayer client. */
    public static void open(EntityPlayer player, IInventory inventory, GrugGuiBuilder layout) {
        if (!(player instanceof EntityPlayerSP)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "GUI.open: Opening a GUI for a player other than the local one is not"
                            + " supported yet.");
            return;
        }

        ModLoader.getMinecraftInstance()
                .displayGuiScreen(
                        new GrugScreen(new GrugContainer(player, inventory, layout), layout));
    }

    /**
     * Installs the client's half of {@link GrugScreenPacket}, which is how a server opens a GUI for
     * this player (#1098). Called by {@code mod_Grug.load} on a client.
     */
    public static void installServerScreens() {
        GrugScreenPacket.clientHandler =
                new GrugScreenPacket.Handler() {
                    @Override
                    public void openScreen(
                            int x, int y, int z, int windowId, GrugGuiBuilder layout) {
                        openFromServer(x, y, z, windowId, layout);
                    }
                };
    }

    /**
     * Shows the GUI a server opened: the same {@link GrugContainer} the server built, from the same
     * layout, on this client's copy of the block entity's inventory. Installing the container as
     * the player's open one is what makes a click send the window id the server's container has, so
     * the click lands in the server's slots rather than in the player's own inventory.
     */
    private static void openFromServer(int x, int y, int z, int windowId, GrugGuiBuilder layout) {
        EntityPlayerSP player = ModLoader.getMinecraftInstance().thePlayer;
        if (player == null) {
            // The block entity's chunk, and this screen with it, can only arrive in a world the
            // player is in, so a packet without one is a screen nothing could show.
            return;
        }

        GrugContainer container =
                new GrugContainer(player, blockInventory(player.worldObj, x, y, z, layout), layout);
        container.windowId = windowId;
        player.craftingInventory = container;
        ModLoader.getMinecraftInstance().displayGuiScreen(new GrugScreen(container, layout));
    }

    /**
     * The inventory the client's container reads: this client's copy of the block entity, or a
     * placeholder of the size the layout's grid needs when that copy has not arrived yet, which a
     * block placed and used in one server tick does (#425). The contents come from the server's
     * window packets either way.
     */
    private static IInventory blockInventory(
            World world, int x, int y, int z, GrugGuiBuilder layout) {
        if (world != null) {
            TileEntity blockEntity = world.getBlockTileEntity(x, y, z);
            if (blockEntity instanceof IInventory) {
                return (IInventory) blockEntity;
            }
        }
        int size = 0;
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            size = Math.max(size, grid.startSlot() + 9);
        }
        return new InventoryBasic("Grug Inventory", size);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        if (!textureLoaded) {
            textureLoaded = true;
            textureId = loadTexture();
        }
        if (textureId < 0) {
            return;
        }

        GlBridge.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.renderEngine.bindTexture(this.textureId);
        this.drawTexturedModalRect(this.guiLeft, this.guiTop, 0, 0, this.xSize, this.ySize);
    }

    @Override
    protected void drawGuiContainerForegroundLayer() {
        // 1.2.5 leaves GL_LIGHTING enabled from the item render that precedes this, so a declared
        // (64, 64, 64) reaches the framebuffer as (86, 86, 86). The reference's GuiAutoCrafting and
        // vanilla's own container labels draw through that light without a shadow, so the port
        // does the same rather than restoring the declared colour.
        for (GrugGuiBuilder.TextDef text : layout.texts) {
            this.fontRenderer.drawString(text.text(), text.x(), text.y(), text.color());
        }
    }

    private int loadTexture() {
        String path = layout.texturePath;
        if (path == null || path.isEmpty()) {
            return -1;
        }

        try {
            File file = new File(GrugCore.getAdapter().getGrugModsDirectory(), path);
            if (!file.isFile()) {
                GrugCore.getAdapter().logError("GUI texture not found: " + file);
                return -1;
            }
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                GrugCore.getAdapter().logError("GUI texture is not a readable image: " + file);
                return -1;
            }
            return this.mc.renderEngine.allocateAndSetupTexture(image);
        } catch (Exception e) {
            GrugCore.getAdapter().logError("Failed to load GUI texture " + path + ": " + e);
            return -1;
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // 1.2.5's RenderEngine keeps a texture cache and has no release call, so just drop the id.
        textureId = -1;
    }
}
