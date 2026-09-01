package net.grug.minecraft.gui;

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
        // Since texturePath is something like "textures/gui/autocrafting_table.png",
        // you might need to resolve it against the mod's specific namespace if needed.
        int bgTextureId = minecraft.textureManager.getTextureId("/" + layout.texturePath);
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
