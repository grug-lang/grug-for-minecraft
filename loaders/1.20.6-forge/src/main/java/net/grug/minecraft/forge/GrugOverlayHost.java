package net.grug.minecraft.forge;

import net.grug.minecraft.forge.gui.GrugScreen;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugObject;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugOverlayGeometry;
import net.grug.minecraft.grug.GrugScreenBox;
import net.grug.minecraft.grug.GrugScreenItemDraw;
import net.grug.minecraft.grug.GrugTestState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Asks the loaded overlays to draw on the player's container screens and to take their clicks.
 *
 * <p>This is the loader's half of the overlay API: it says which screens an overlay is asked about
 * and it is where the recorded icons and boxes are drawn, because those have to land in the {@link
 * GuiGraphics} the open screen is rendering into. Opening and closing the pass, and handing the
 * export its context, is core's {@link GrugOverlay}.
 *
 * <p>Only {@link AbstractContainerScreen} is eligible, which is what covers the player's inventory,
 * a chest and a crafting table on this version, and only once the screen has a size. That is the
 * same set of screens and the same guard that JEI itself uses.
 *
 * <p>The overlay's entity is created on the first eligible screen rather than at load, so its
 * member scope runs after every item is registered: an overlay that asks the host for an item by
 * index would otherwise see only the items that happened to be registered when the mod was
 * constructed.
 */
public final class GrugOverlayHost {

    /**
     * The highlight JEI draws over a hovered slot: white at half opacity, over the whole 18 by 18
     * slot rather than over the 16 by 16 icon, which is what the reference's own {@code
     * IngredientGrid.drawHighlight} fills.
     */
    private static final int HIGHLIGHT = 0x80FFFFFF;

    /** The overlay files the loader found, by clean name, so the first screen can create them. */
    private static Map<String, Long> overlayFileIds = new TreeMap<>();

    /** The mod each overlay file belongs to, by the same clean name. */
    private static Map<String, String> overlayMods = new TreeMap<>();

    private static final List<Overlay> overlays = new ArrayList<>();
    private static boolean created = false;

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
     * The overlay files to ask, by clean name, from the mod loader's file classification, with the
     * mod each belongs to, and the mark that this loader draws overlays at all, which a test asks
     * before it opens a screen.
     */
    static void init(Map<String, Long> files, Map<String, String> mods) {
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
     * Draws every overlay's render() on top of the screen's own frame.
     *
     * <p>Render.Post fires after the screen and its tooltip were drawn, which is where an overlay
     * has to draw to be on top of both. The icons go down before the highlight, so a hovered slot's
     * box lies over its icon, as in the reference.
     */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        AbstractContainerScreen<?> container = eligible(event.getScreen());
        if (container == null) return;

        ensureCreated();

        GuiGraphics graphics = event.getGuiGraphics();
        String testMod = GrugTestState.currentTestMod;
        for (Overlay overlay : overlays) {
            GrugOverlayGeometry geometry =
                    GrugOverlay.render(
                            overlay.entityHandle,
                            overlay.renderFnId,
                            container.width,
                            container.height,
                            container.getGuiLeft(),
                            container.getGuiTop(),
                            container.getXSize(),
                            container.getYSize(),
                            event.getMouseX(),
                            event.getMouseY());

            // A test waits for its own mod's overlay, not for another mod's on the same screen: the
            // coverage mod's overlay draws on every screen too, and would otherwise hide a port
            // whose render export never ran or declined to draw. A render with nothing to draw is
            // not a frame of the test's list.
            if (!geometry.items().isEmpty() || !geometry.boxes().isEmpty()) {
                if (overlay.mod.equals(testMod)) {
                    GrugOverlay.noteDrawn();
                }
            }

            for (GrugScreenItemDraw item : geometry.items()) {
                graphics.renderItem(
                        new ItemStack((Item) item.item()), (int) item.x(), (int) item.y());
            }
            for (GrugScreenBox box : geometry.boxes()) {
                graphics.fill(
                        (int) box.x1(), (int) box.y1(), (int) box.x2(), (int) box.y2(), HIGHLIGHT);
            }
        }
    }

    /**
     * Clears the drawn mark when an eligible screen opens, so a test waits for a frame of the
     * screen it just opened rather than reading a mark another test's screen left behind.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (eligible(event.getScreen()) == null) return;
        GrugOverlay.screenOpened();
    }

    /**
     * Lets an overlay take a mouse press before the screen's own handler sees it.
     *
     * <p>The press is cancelled when an overlay answers that it took it, so the vanilla slot under
     * the clicked icon is not also clicked. Asking stops at the first overlay that takes the press:
     * two overlays over the same icon would otherwise both give their item for one click.
     */
    @SubscribeEvent
    public static void onScreenMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        AbstractContainerScreen<?> container = eligible(event.getScreen());
        if (container == null) return;

        ensureCreated();

        for (Overlay overlay : overlays) {
            boolean taken =
                    GrugOverlay.click(
                            overlay.entityHandle,
                            overlay.clickFnId,
                            container.width,
                            container.height,
                            container.getGuiLeft(),
                            container.getGuiTop(),
                            container.getXSize(),
                            container.getYSize(),
                            event.getMouseX(),
                            event.getMouseY(),
                            event.getButton());
            if (taken) {
                GrugOverlay.noteTestPressTaken();
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * The container screen an overlay may draw on, or null when this screen is not one or has no
     * size yet, which is what JEI's own screen check answers.
     *
     * <p>A grug mod's own {@link GrugScreen} is left out on purpose: the first version draws on the
     * player's inventory and on vanilla chest and crafting-table screens, and drawing on a mod's
     * own panel would change the GUI goldens of the mods that have one. JEI itself draws there, so
     * about.json records the difference.
     */
    private static AbstractContainerScreen<?> eligible(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) return null;
        if (container instanceof GrugScreen) return null;
        if (container.width <= 0 || container.height <= 0) return null;
        return container;
    }
}
