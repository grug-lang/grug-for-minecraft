package net.grug.minecraft.forge125.client;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntityPlayerSP;
import net.minecraft.src.GuiContainer;
import net.minecraft.src.IInventory;
import net.minecraft.src.ModLoader;

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
