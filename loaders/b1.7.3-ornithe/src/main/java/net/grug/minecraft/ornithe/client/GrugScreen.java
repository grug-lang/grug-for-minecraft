package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.gui.GrugMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.entity.mob.player.LocalClientPlayerEntity;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;
import org.lwjgl.opengl.GL11;

/** Client-only. Never reference this class from code that runs on a dedicated server. */
public class GrugScreen extends InventoryMenuScreen {
    private final GrugGuiBuilder layout;
    private final String texturePath;

    public GrugScreen(GrugMenu menu, GrugGuiBuilder layout) {
        super(menu);
        this.layout = layout;
        this.texturePath = toResourcePath(layout.texturePath);
    }

    /**
     * Grug gives paths like "buildcraft/assets/grug/textures/gui/crafting.png".
     * GrugResourcePack resolves paths relative to a mod dir, so drop the mod name.
     */
    private static String toResourcePath(String path) {
        int assetsIdx = path.indexOf("/assets/");
        return assetsIdx == -1 ? path : path.substring(assetsIdx + 1);
    }

    /**
     * Opens the screen for a singleplayer client. Beta 1.7.3 singleplayer has no
     * server, so `Block.use` runs on the client and no packets are needed.
     */
    public static void open(PlayerEntity player, Inventory inventory, GrugGuiBuilder layout) {
        if (!(player instanceof ClientPlayerEntity) || player instanceof LocalClientPlayerEntity) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "GUI.open: Opening a GUI in multiplayer is not supported yet.");
            return;
        }

        Minecraft minecraft = MinecraftInstance.get();
        minecraft.openScreen(new GrugScreen(new GrugMenu(player, inventory, layout), layout));
    }

    @Override
    protected void renderMenuBackground(float tickDelta) {
        int texture = minecraft.textureManager.load(texturePath);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        minecraft.textureManager.bind(texture);
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        drawTexture(x, y, 0, 0, backgroundWidth, backgroundHeight);
    }

    @Override
    protected void renderLabels() {
        for (GrugGuiBuilder.TextDef text : layout.texts) {
            textRenderer.draw(text.text(), text.x(), text.y(), text.color());
        }
    }
}
