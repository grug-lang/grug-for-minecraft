package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.entity.mob.player.LocalClientPlayerEntity;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuSlot;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.entity.mob.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;
import net.ornithemc.osl.resource.loader.api.resource.manager.ResourceManager;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/** Client-only. Never reference this class from code that runs on a dedicated server. */
public class GrugScreen extends InventoryMenuScreen {
    private final GrugGuiBuilder layout;
    private final String texturePath;
    private boolean textureLoaded;
    private int textureId = -1;

    /** In Alpha the screen itself owns the slots, there is no separate menu object. */
    public GrugScreen(PlayerInventory playerInventory, Inventory blockInventory, GrugGuiBuilder layout) {
        super();
        this.layout = layout;
        this.texturePath = toResourcePath(layout.texturePath);

        for (GrugGuiBuilder.SlotDef def : layout.blockSlots) {
            this.menuSlots.add(new InventoryMenuSlot(this, blockInventory, def.index(), def.x(), def.y()));
        }

        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    this.menuSlots.add(new InventoryMenuSlot(this, blockInventory, grid.startSlot() + col + row * 3,
                            grid.x() + col * 18, grid.y() + row * 18));
                }
            }
        }

        for (GrugGuiBuilder.CraftingResultDef res : layout.craftingResults) {
            this.menuSlots.add(new InventoryMenuSlot(this, blockInventory, res.slot(), res.x(), res.y()) {
                @Override
                public boolean isItemAllowed(ItemStack stack) {
                    return false;
                }

                @Override
                public void onItemRemoved() {
                    super.onItemRemoved();
                    if (blockInventory instanceof GrugBlockEntity gbe) {
                        // Alpha does not tell us which stack was taken, so the amount is unknown
                        gbe.notifyOutputTaken(res.slot(), 0);
                    }
                }
            });
        }

        if (layout.hasPlayerInventory) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.menuSlots.add(new InventoryMenuSlot(this, playerInventory, col + row * 9 + 9,
                            layout.playerInvX + col * 18, layout.playerInvY + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.menuSlots.add(new InventoryMenuSlot(this, playerInventory, col,
                        layout.hotbarX + col * 18, layout.hotbarY));
            }
        }
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
     * Opens the screen for a singleplayer client. Alpha singleplayer has no
     * server, so `Block.use` runs on the client and no packets are needed.
     */
    public static void open(PlayerEntity player, Inventory inventory, GrugGuiBuilder layout) {
        if (!(player instanceof ClientPlayerEntity) || player instanceof LocalClientPlayerEntity) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "GUI.open: Opening a GUI in multiplayer is not supported yet.");
            return;
        }

        MinecraftInstance.get().openScreen(new GrugScreen(player.inventory, inventory, layout));
    }

    /**
     * Alpha has no texture pack layer, so TextureManager.load(String) cannot see the
     * mod directory. Read the image through OSL's resource manager instead.
     */
    private int loadTexture() {
        try (InputStream is = ResourceManager.client().getResource(texturePath)) {
            if (is == null) {
                GrugModLoader.LOGGER.error("GUI texture not found: {}", texturePath);
                return -1;
            }
            BufferedImage image = ImageIO.read(is);
            if (image == null) {
                GrugModLoader.LOGGER.error("GUI texture is not a readable image: {}", texturePath);
                return -1;
            }
            return minecraft.textureManager.load(image);
        } catch (Exception e) {
            GrugModLoader.LOGGER.error("Failed to load GUI texture " + texturePath, e);
            return -1;
        }
    }

    @Override
    protected void renderMenuBackground(float tickDelta) {
        if (!textureLoaded) {
            textureLoaded = true;
            textureId = loadTexture();
        }
        if (textureId < 0) {
            return;
        }

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        minecraft.textureManager.bind(textureId);
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

    @Override
    public void removed() {
        super.removed();
        if (textureId >= 0) {
            minecraft.textureManager.release(textureId);
            textureId = -1;
        }
    }
}
