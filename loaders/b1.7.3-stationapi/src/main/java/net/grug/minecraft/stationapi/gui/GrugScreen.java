package net.grug.minecraft.stationapi.gui;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.lwjgl.opengl.GL11;

public class GrugScreen extends HandledScreen {
    private final GrugGuiBuilder layout;

    public GrugScreen(GrugScreenHandler handler, GrugGuiBuilder layout) {
        super(handler);
        this.layout = layout;
    }

    @Override
    protected void drawBackground(float tickDelta) {
        String path = layout.texturePath;
        if (path != null) {
            // Convert disk paths like "buildcraft/assets/grug/textures/gui/crafting.png"
            // into valid Identifiers like "grug:textures/gui/crafting.png"
            int assetsIdx = path.indexOf("/assets/");
            if (assetsIdx != -1) {
                String afterAssets = path.substring(assetsIdx + 8);
                int slashIdx = afterAssets.indexOf('/');
                if (slashIdx != -1) {
                    path = afterAssets.substring(0, slashIdx) + ":" + afterAssets.substring(slashIdx + 1);
                }
            }
        }

        int bgTextureId = minecraft.textureManager.getTextureId(path);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        minecraft.textureManager.bindTexture(bgTextureId);
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        drawTexture(x, y, 0, 0, backgroundWidth, backgroundHeight);
    }

    @Override
    protected void drawForeground() {
        for (GrugGuiBuilder.TextDef textDef : layout.texts) {
            textRenderer.draw(textDef.text(), textDef.x(), textDef.y(), textDef.color());
        }
    }
}
