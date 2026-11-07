package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.GrugAlphaMenu;
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.grug.minecraft.ornithe.gui.GrugScreenClickPacket;
import net.grug.minecraft.ornithe.gui.GrugScreenClosePacket;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.entity.mob.player.LocalClientPlayerEntity;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuSlot;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.entity.mob.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Connection;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;
import net.ornithemc.osl.resource.loader.api.resource.manager.ResourceManager;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

/** Client-only. Never reference this class from code that runs on a dedicated server. */
public class GrugScreen extends InventoryMenuScreen {
    private final GrugGuiBuilder layout;
    private final GrugAlphaMenu menu;
    private final String texturePath;
    private boolean textureLoaded;
    private int textureId = -1;

    /**
     * The window id a server installed the menu under, or -1 for a screen the client opened itself.
     * A server-held screen sends its clicks to the server and never applies one to its own copy.
     */
    private final int windowId;

    /** A GUI the client opens itself, in singleplayer, where it also owns the slots. */
    public GrugScreen(
            PlayerInventory playerInventory, Inventory blockInventory, GrugGuiBuilder layout) {
        this(GrugAlphaMenu.build(playerInventory, blockInventory, layout), layout);
    }

    private GrugScreen(GrugAlphaMenu menu, GrugGuiBuilder layout) {
        super();
        this.menu = menu;
        this.layout = layout;
        this.windowId = menu.windowId;
        this.texturePath = toResourcePath(layout.texturePath);

        for (GrugAlphaMenu.Slot slot : menu.slots) {
            if (slot.result) {
                this.menuSlots.add(
                        new InventoryMenuSlot(this, slot.inventory, slot.index, slot.x, slot.y) {
                            @Override
                            public boolean isItemAllowed(ItemStack stack) {
                                return false;
                            }

                            @Override
                            public void onItemRemoved() {
                                super.onItemRemoved();
                                // A server-held screen's click is the server's, so only a local
                                // screen tells the block entity: Alpha does not say which stack was
                                // taken, so the amount is unknown.
                                if (windowId < 0 && menu.blockEntity != null) {
                                    menu.blockEntity.notifyOutputTaken(0);
                                }
                            }
                        });
            } else {
                this.menuSlots.add(
                        new InventoryMenuSlot(this, slot.inventory, slot.index, slot.x, slot.y));
            }
        }
    }

    /**
     * Shows the GUI a server opened for this client (#1098): the same slot table the server built,
     * from the layout it sent, over this client's copy of the block entity or over a placeholder
     * that arrived no block entity yet (#425). The contents arrive in the content packet that
     * follows the open, which the client tick applies after this.
     */
    public static GrugScreen openFromServer(
            int x, int y, int z, int windowId, GrugGuiBuilder layout) {
        ClientPlayerEntity player = MinecraftInstance.get().player;
        if (player == null) {
            // Only a client in a world can show this, and the packet came over that world's
            // connection, so a packet without a player is one no screen could be drawn for.
            return null;
        }
        GrugAlphaMenu menu =
                GrugAlphaMenu.build(
                        player.inventory,
                        GrugAlphaMenu.clientInventory(
                                MinecraftInstance.get().world, x, y, z, layout),
                        layout);
        menu.windowId = windowId;
        GrugScreen screen = new GrugScreen(menu, layout);
        MinecraftInstance.get().openScreen(screen);
        ClientGrugScreens.opened(screen);
        return screen;
    }

    /**
     * Applies the slot contents and the carried stack a server sent for this screen. A packet for a
     * menu it has already replaced is dropped: its window id is not this screen's.
     */
    public void applyContents(int windowId, ItemStack cursor, ItemStack[] contents) {
        if (this.windowId != windowId) {
            return;
        }
        GrugAlphaMenu.applyContents(menu, contents);
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.inventory.cursorItem = cursor;
        }
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
     * Opens the screen for a singleplayer client. Alpha singleplayer has no server, so `Block.use`
     * runs on the client and no packets are needed.
     */
    public static void open(PlayerEntity player, Inventory inventory, GrugGuiBuilder layout) {
        if (!(player instanceof ClientPlayerEntity) || player instanceof LocalClientPlayerEntity) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "GUI.open: Opening a GUI in multiplayer is not supported yet.");
            return;
        }

        MinecraftInstance.get().openScreen(new GrugScreen(player.inventory, inventory, layout));
    }

    /**
     * A click on a server-held screen is the server's: this sends which slot was pressed and never
     * touches the client's copy, which the server's content packet then replaces. A singleplayer
     * screen keeps the game's own click path.
     *
     * <p>The overlay is asked first, exactly as the shared screen mixin asks it at the head of the
     * game's own handler, which this override replaces for a server-held screen.
     */
    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (windowId < 0) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }
        if (button != 0 && button != 1) {
            return;
        }
        boolean shift =
                Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (GrugOverlayHost.click(this, mouseX, mouseY, button, shift)) {
            return;
        }
        int slot = slotAt(mouseX, mouseY);
        if (slot == GrugScreenClickPacket.OUTSIDE && !outsidePanel(mouseX, mouseY)) {
            // The game's own handler drops the cursor only for a click outside the panel; one on
            // the panel's background but on no slot does nothing, so this sends nothing either.
            return;
        }
        Connection connection = GrugControlAlpha.clientConnectionForScreens();
        if (connection != null) {
            connection.send(new GrugScreenClickPacket(windowId, slot, button));
        }
    }

    /** The index of the slot under the point in the menu's own order, or -1 for none. */
    private int slotAt(int mouseX, int mouseY) {
        for (int i = 0; i < menuSlots.size(); i++) {
            if (((InventoryMenuSlot) menuSlots.get(i)).mouseClicked(mouseX, mouseY)) {
                return i;
            }
        }
        return GrugScreenClickPacket.OUTSIDE;
    }

    /**
     * The game's own test for a click outside the panel, which uses the panel's width for the
     * bottom edge as Alpha does. A drop that only happened where the game drops one keeps the
     * server's carried stack in the same cases the client's screen would have kept it.
     */
    private boolean outsidePanel(int mouseX, int mouseY) {
        int guiLeft = (width - backgroundWidth) / 2;
        int guiTop = (height - backgroundHeight) / 2;
        return mouseX < guiLeft
                || mouseY < guiTop
                || mouseX >= guiLeft + backgroundWidth
                || mouseY >= guiTop + backgroundWidth;
    }

    /**
     * Alpha has no texture pack layer, so TextureManager.load(String) cannot see the mod directory.
     * Read the image through OSL's resource manager instead.
     *
     * <p>The path comes from the script's own {@code gui(...)} call, so a texture it cannot find is
     * the script's err rather than the host's: {@code GUI.open} reports it and the screen opens
     * without a background instead of the mod being broken by a path it never named.
     */
    private int loadTexture() {
        try (InputStream is = ResourceManager.client().getResource(texturePath)) {
            BufferedImage image = ImageIO.read(is);
            if (image == null) {
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr,
                        "GUI.open: The texture " + texturePath + " is not an image.");
                return -1;
            }
            return minecraft.textureManager.load(image);
        } catch (java.io.FileNotFoundException missing) {
            // The resource manager reports a texture no mod ships by throwing rather than by
            // returning nothing, so this is where a script naming one it does not have lands.
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "GUI.open: There is no texture at " + texturePath + ".");
            return -1;
        } catch (java.io.IOException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "GUI.open: Failed to read the texture at " + texturePath + ".");
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
        if (windowId >= 0) {
            // The carried stack belongs to the server's menu, so this only tells the server the
            // screen is gone and lets it drop the stack as the game's own close does. Clearing the
            // client's copy first keeps the game's handler below from dropping a second one into
            // this client's world, where nothing would ever be simulated.
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.inventory.cursorItem = null;
            }
            Connection connection = GrugControlAlpha.clientConnectionForScreens();
            if (connection != null) {
                connection.send(new GrugScreenClosePacket(windowId));
            }
            ClientGrugScreens.closed(this);
        }
        super.removed();
        if (textureId >= 0) {
            minecraft.textureManager.release(textureId);
            textureId = -1;
        }
    }
}
