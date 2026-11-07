package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.gui.GrugMenu;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.entity.mob.player.LocalClientPlayerEntity;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.world.World;
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
     * Grug gives paths like "buildcraft/assets/grug/textures/gui/crafting.png". GrugResourcePack
     * resolves paths relative to a mod dir, so drop the mod name.
     */
    private static String toResourcePath(String path) {
        int assetsIdx = path.indexOf("/assets/");
        return assetsIdx == -1 ? path : path.substring(assetsIdx + 1);
    }

    /**
     * Opens the screen for a singleplayer client. Beta 1.7.3 singleplayer has no server, so
     * `Block.use` runs on the client and no packets are needed.
     */
    public static void open(PlayerEntity player, Inventory inventory, GrugGuiBuilder layout) {
        if (!(player instanceof ClientPlayerEntity) || player instanceof LocalClientPlayerEntity) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "GUI.open: Opening a GUI in multiplayer is not supported yet.");
            return;
        }

        Minecraft minecraft = MinecraftInstance.get();
        minecraft.openScreen(new GrugScreen(new GrugMenu(player, inventory, layout), layout));
    }

    /**
     * Shows the GUI a server opened for this client (#1098): the same {@link GrugMenu} the server
     * built, from the same layout, on this client's copy of the block entity's inventory. The
     * server has already installed it as the player's open menu, and {@link
     * InventoryMenuScreen#init} installs this client's copy as the player's, so a click carries the
     * network id the server's menu has and lands in the server's slots rather than in the player's
     * own inventory.
     */
    public static void openFromServer(int x, int y, int z, int windowId, GrugGuiBuilder layout) {
        Minecraft minecraft = MinecraftInstance.get();
        ClientPlayerEntity player = minecraft.player;
        if (player == null) {
            // The block entity's chunk, and this screen with it, can only arrive in a world the
            // player is in, so a packet without one is a screen nothing could show.
            return;
        }

        GrugMenu menu = new GrugMenu(player, blockInventory(player.world, x, y, z, layout), layout);
        menu.networkId = windowId;
        minecraft.openScreen(new GrugScreen(menu, layout));
    }

    /**
     * The inventory the client's menu reads: this client's copy of the block entity, or a
     * placeholder of the size the layout's grid needs when that copy has not arrived yet, which a
     * block placed and used in one server tick does (#425). The contents come from the server's
     * window packets either way.
     */
    private static Inventory blockInventory(
            World world, int x, int y, int z, GrugGuiBuilder layout) {
        if (world != null) {
            BlockEntity blockEntity = world.getBlockEntity(x, y, z);
            if (blockEntity instanceof Inventory) {
                return (Inventory) blockEntity;
            }
        }
        int size = 0;
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            size = Math.max(size, grid.startSlot() + 9);
        }
        return new SimpleInventory("Grug Inventory", size);
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
