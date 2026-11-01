package net.grug.minecraft.forge125.client;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugObject;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugOverlayGeometry;
import net.grug.minecraft.grug.GrugScreenBox;
import net.grug.minecraft.grug.GrugScreenItemDraw;
import net.grug.minecraft.grug.GrugTestState;
import net.minecraft.client.Minecraft;
import net.minecraft.src.GrugGuiAccess;
import net.minecraft.src.Gui;
import net.minecraft.src.GuiContainer;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.RenderHelper;
import net.minecraft.src.RenderItem;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Asks the loaded overlays to draw on the player's container screens and to take their clicks on
 * 1.2.5.
 *
 * <p>1.2.5 has no screen event bus, so this loader hooks the frame and the mouse from its tick
 * handler instead: {@link GrugClientHooks} draws the recorded icons and boxes just after the screen
 * has drawn, and routes every mouse event through here before the vanilla screen sees it. That is
 * the same order the reference's overlay gets, without an event the version does not have.
 *
 * <p>Only {@link GuiContainer} is eligible, which covers the player's inventory, a chest and a
 * crafting table, and a grug mod's own {@link GrugScreen} is left out so its GUI goldens do not
 * change. The overlay's entity is created on the first eligible screen rather than at load, so its
 * member scope runs after every item is registered.
 */
public final class GrugOverlayHost {

    /** The highlight JEI draws over a hovered slot: white at half opacity. */
    private static final int HIGHLIGHT = 0x80FFFFFF;

    /** The overlay files the loader found, by clean name, so the first screen can create them. */
    private static Map<String, Long> overlayFileIds = new TreeMap<>();

    /** The mod each overlay file belongs to, by the same clean name. */
    private static Map<String, String> overlayMods = new TreeMap<>();

    private static final List<Overlay> overlays = new ArrayList<>();
    private static boolean created = false;

    /** The screen the last frame drew on, so a newly opened screen clears the drawn mark. */
    private static GuiScreen lastScreen = null;

    /** The item renderer, made once because the game draws every icon through it. */
    private static RenderItem renderItem = null;

    /** The mouse position of the last real event, which the render pass reports while it hovers. */
    private static int mouseX = 0;

    private static int mouseY = 0;

    @GrugGenerated("utility class: never instantiated")
    private GrugOverlayHost() {}

    private static final class Overlay {
        final long entityHandle;
        final List<GrugObject> owner;
        final long renderFnId;
        final long clickFnId;
        final String mod;

        Overlay(
                long entityHandle,
                List<GrugObject> owner,
                long renderFnId,
                long clickFnId,
                String mod) {
            this.entityHandle = entityHandle;
            this.owner = owner;
            this.renderFnId = renderFnId;
            this.clickFnId = clickFnId;
            this.mod = mod;
        }
    }

    /**
     * The overlay files to ask, by clean name, with the mod each belongs to, and the mark that this
     * loader draws overlays at all, which a test asks before it opens a screen.
     */
    public static void init(Map<String, Long> files, Map<String, String> mods) {
        overlayFileIds = new TreeMap<>(files);
        overlayMods = new TreeMap<>(mods);
        GrugOverlay.noteSupported();
    }

    private static void ensureCreated() {
        if (created) return;
        created = true;

        for (Map.Entry<String, Long> entry : overlayFileIds.entrySet()) {
            List<GrugObject> owner = new ArrayList<>();
            long handle = GrugEntities.createEntityWithOwner(entry.getValue(), owner, null);
            if (handle == 0) continue;
            overlays.add(
                    new Overlay(
                            handle,
                            owner,
                            Grug.getExportFnId("Overlay", "render"),
                            Grug.getExportFnId("Overlay", "click"),
                            overlayMods.getOrDefault(entry.getKey(), "")));
        }
    }

    /**
     * Draws every overlay on top of the screen's own frame.
     *
     * <p>1.2.5's render tick ends immediately after {@code updateCameraAndRender}, which is where
     * {@link GrugClientHooks} calls this: the screen and its tooltip are already drawn, and the
     * frame has not been presented yet. Icons go down before the highlight, so a hovered slot's box
     * lies over its icon, as in the reference.
     */
    public static void renderCurrent(Minecraft minecraft) {
        GuiContainer container = eligible(minecraft.currentScreen);
        if (container == null) {
            lastScreen = null;
            return;
        }

        if (container != lastScreen) {
            GrugOverlay.screenOpened();
            lastScreen = container;
        }

        ensureCreated();
        readMouse(minecraft, container);

        if (renderItem == null) {
            renderItem = new RenderItem();
        }

        String testMod = GrugTestState.currentTestMod;
        for (Overlay overlay : overlays) {
            GrugOverlayGeometry geometry =
                    GrugOverlay.render(
                            overlay.entityHandle,
                            overlay.renderFnId,
                            container.width,
                            container.height,
                            GrugGuiAccess.guiLeft(container),
                            GrugGuiAccess.guiTop(container),
                            GrugGuiAccess.xSize(container),
                            GrugGuiAccess.ySize(container),
                            mouseX,
                            mouseY);

            if (!geometry.items().isEmpty() || !geometry.boxes().isEmpty()) {
                if (overlay.mod.equals(testMod)) {
                    GrugOverlay.noteDrawn();
                }
            }

            RenderHelper.enableGUIStandardItemLighting();
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            renderItem.zLevel = 200.0F;
            for (GrugScreenItemDraw item : geometry.items()) {
                renderItem.renderItemIntoGUI(
                        minecraft.fontRenderer,
                        minecraft.renderEngine,
                        new ItemStack((Item) item.item()),
                        (int) item.x(),
                        (int) item.y());
            }
            renderItem.zLevel = 0.0F;
            RenderHelper.disableStandardItemLighting();
            for (GrugScreenBox box : geometry.boxes()) {
                Gui.drawRect(
                        (int) box.x1(), (int) box.y1(), (int) box.x2(), (int) box.y2(), HIGHLIGHT);
            }
        }
    }

    /**
     * Routes a real mouse event through the overlays before the screen sees it.
     *
     * <p>The game would otherwise consume the event in {@code GuiScreen.handleInput} after this
     * tick, so an eligible screen's events are read and dispatched here, and the game's own loop
     * then finds the queue empty. A press an overlay takes never reaches the vanilla slot under it;
     * one no overlay takes is passed on exactly as the screen's own handler would pass it.
     */
    public static void handleMouse(Minecraft minecraft) {
        GuiContainer container = eligible(minecraft.currentScreen);
        if (container == null) return;

        while (Mouse.next()) {
            int x = Mouse.getEventX() * container.width / minecraft.displayWidth;
            int y =
                    container.height
                            - Mouse.getEventY() * container.height / minecraft.displayHeight
                            - 1;
            mouseX = x;
            mouseY = y;
            int button = Mouse.getEventButton();
            if (Mouse.getEventButtonState()) {
                boolean taken = click(container, x, y, button, GuiScreen.isShiftKeyDown());
                if (!taken) {
                    GrugGuiAccess.mouseClicked(container, x, y, button);
                }
            } else {
                GrugGuiAccess.mouseMovedOrUp(container, x, y, button);
            }
        }
    }

    /**
     * Asks the overlays about one press and answers whether one took it, recording the answer for
     * {@code Test.click_screen}.
     */
    public static boolean click(GuiScreen screen, int x, int y, int button, boolean shift) {
        GuiContainer container = eligible(screen);
        if (container == null) return false;

        ensureCreated();
        for (Overlay overlay : overlays) {
            boolean taken =
                    GrugOverlay.click(
                            overlay.entityHandle,
                            overlay.clickFnId,
                            container.width,
                            container.height,
                            GrugGuiAccess.guiLeft(container),
                            GrugGuiAccess.guiTop(container),
                            GrugGuiAccess.xSize(container),
                            GrugGuiAccess.ySize(container),
                            x,
                            y,
                            button,
                            shift);
            if (taken) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return false;
    }

    /** The container screen an overlay may draw on, or null when this screen is not one. */
    private static GuiContainer eligible(GuiScreen screen) {
        if (!(screen instanceof GuiContainer)) return null;
        GuiContainer container = (GuiContainer) screen;
        if (container instanceof GrugScreen) return null;
        if (container.width <= 0 || container.height <= 0) return null;
        return container;
    }

    /**
     * The scaled mouse position, in the same pixels a screen's mouse handlers take. LWJGL counts y
     * from the bottom of the window, so it is flipped here the way {@code GuiScreen} flips it.
     */
    private static void readMouse(Minecraft minecraft, GuiScreen screen) {
        mouseX = Mouse.getX() * screen.width / minecraft.displayWidth;
        mouseY = screen.height - Mouse.getY() * screen.height / minecraft.displayHeight - 1;
    }
}
