package net.grug.minecraft.forge.gui;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class GrugScreen extends AbstractContainerScreen<GrugMenu> {
    private final GrugGuiBuilder layout;
    private final ResourceLocation backgroundTexture;

    public GrugScreen(GrugMenu menu, Inventory playerInventory, Component title, GrugGuiBuilder layout) {
        super(menu, playerInventory, title);
        this.layout = layout;

        String path = (layout != null && layout.texturePath != null) ? layout.texturePath
                : "textures/gui/container/dispenser.png";
        if (!path.contains(":")) {
            path = "minecraft:" + path;
        }
        this.backgroundTexture = new ResourceLocation(path);
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        guiGraphics.blit(this.backgroundTexture, x, y, 0, 0, this.imageWidth, this.imageHeight);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (layout != null && layout.texts != null) {
            for (GrugGuiBuilder.TextDef textDef : layout.texts) {
                guiGraphics.drawString(this.font, textDef.text(), textDef.x(), textDef.y(), textDef.color(), false);
            }
        }
    }
}
