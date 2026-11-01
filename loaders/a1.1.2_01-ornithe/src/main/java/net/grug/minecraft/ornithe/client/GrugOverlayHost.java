package net.grug.minecraft.ornithe.client;

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
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.render.entity.ItemRenderer;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Asks the loaded overlays to draw on the player's container screens and to take their clicks on
 * Alpha 1.1.2_01 Ornithe.
 *
 * <p>The screen hooks live in the shared {@code InventoryMenuScreenMixin}, because this version has
 * no screen event a mod can subscribe to; this class is the half that owns the overlay entities and
 * turns a pass's recorded geometry into the game's own item drawing.
 */
public final class GrugOverlayHost {

    /** The overlay files the loader found, by clean name, so the first screen can create them. */
    private static Map<String, Long> overlayFileIds = new TreeMap<>();

    /** The mod each overlay file belongs to, by the same clean name. */
    private static Map<String, String> overlayMods = new TreeMap<>();

    private static final List<Overlay> overlays = new ArrayList<>();
    private static boolean created = false;
    private static ItemRenderer renderItem = null;

    /** The last screen rendered, so a newly opened screen clears the drawn mark. */
    private static Screen lastScreen = null;

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
        // The maps are the loader's own, filled by its initializer; holding them rather than
        // copying lets the client initializer run before or after that pass and still see every
        // file.
        overlayFileIds = files;
        overlayMods = mods;
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
     * Draws every overlay on top of the screen's own frame and answers the boxes the caller still
     * has to fill.
     *
     * <p>The icons are drawn here because item drawing belongs to the game's renderer; the
     * highlight boxes go back to the mixin, which is the only place with the screen's own fill.
     */
    public static List<GrugScreenBox> render(Screen screen, int mouseX, int mouseY) {
        GrugScreenGeometry geometry = geometry(screen);
        if (geometry == null) {
            lastScreen = null;
            return Collections.emptyList();
        }

        if (screen != lastScreen) {
            GrugOverlay.screenOpened();
            lastScreen = screen;
        }

        ensureCreated();
        if (renderItem == null) {
            renderItem = new ItemRenderer();
        }

        Minecraft minecraft = MinecraftInstance.get();
        String testMod = GrugTestState.currentTestMod;
        List<GrugScreenBox> boxes = new ArrayList<>();
        for (Overlay overlay : overlays) {
            GrugOverlayGeometry drawn =
                    GrugOverlay.render(
                            overlay.entityHandle,
                            overlay.renderFnId,
                            geometry.grug$width(),
                            geometry.grug$height(),
                            geometry.grug$guiLeft(),
                            geometry.grug$guiTop(),
                            geometry.grug$guiWidth(),
                            geometry.grug$guiHeight(),
                            mouseX,
                            mouseY);

            if (!drawn.items().isEmpty() || !drawn.boxes().isEmpty()) {
                if (overlay.mod.equals(testMod)) {
                    GrugOverlay.noteDrawn();
                }
            }

            Lighting.turnOn();
            for (GrugScreenItemDraw item : drawn.items()) {
                renderItem.renderGuiItem(
                        minecraft.textRenderer,
                        minecraft.textureManager,
                        new ItemStack((Item) item.item()),
                        (int) item.x(),
                        (int) item.y());
            }
            Lighting.turnOff();
            boxes.addAll(drawn.boxes());
        }
        return boxes;
    }

    /**
     * Asks the overlays about one press and answers whether one took it, recording the answer for
     * {@code Test.click_screen}.
     */
    public static boolean click(Screen screen, int x, int y, int button, boolean shift) {
        GrugScreenGeometry geometry = geometry(screen);
        if (geometry == null) return false;

        ensureCreated();
        for (Overlay overlay : overlays) {
            boolean taken =
                    GrugOverlay.click(
                            overlay.entityHandle,
                            overlay.clickFnId,
                            geometry.grug$width(),
                            geometry.grug$height(),
                            geometry.grug$guiLeft(),
                            geometry.grug$guiTop(),
                            geometry.grug$guiWidth(),
                            geometry.grug$guiHeight(),
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

    /** Whether an overlay may draw on this screen, and the geometry it draws through. */
    public static GrugScreenGeometry geometry(Screen screen) {
        if (!(screen instanceof InventoryMenuScreen)) return null;
        if (screen instanceof GrugScreen) return null;
        if (screen.width <= 0 || screen.height <= 0) return null;
        return (GrugScreenGeometry) screen;
    }
}
