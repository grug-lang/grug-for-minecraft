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

import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Asks the loaded overlays to draw on the player's container screens and to take their clicks on
 * Beta 1.7.3 Ornithe.
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

    @GrugGenerated("utility class: never instantiated")
    private GrugOverlayHost() {}

    private static final class Overlay {
        final long entityHandle;
        final List<GrugObject> owner;
        final long renderFnId;
        final long clickFnId;
        final long keyFnId;
        final String mod;

        Overlay(
                long entityHandle,
                List<GrugObject> owner,
                long renderFnId,
                long clickFnId,
                long keyFnId,
                String mod) {
            this.entityHandle = entityHandle;
            this.owner = owner;
            this.renderFnId = renderFnId;
            this.clickFnId = clickFnId;
            this.keyFnId = keyFnId;
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
                            Grug.getExportFnId("Overlay", "key"),
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
        if (geometry == null) return Collections.emptyList();

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
                renderItem.renderGuiItemWithEnchantmentGlint(
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

    /** Clears the drawn mark when a screen opens, before its first frame. */
    public static void screenOpened(Object screen) {
        GrugOverlay.screenOpened();
    }

    /** Clears the drawn mark when a screen closes. */
    public static void screenClosed() {
        GrugOverlay.screenOpened();
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

    /**
     * Asks the overlays about one key press and answers whether one took it, recording the answer
     * for {@code Test.press_screen_key}.
     *
     * <p>Only a letter or a digit key is asked, given as its upper case character code, which is
     * the same number on every loader. A key the game reports again while it is held is a repeat
     * and is not asked, so a toggle flips once per press.
     */
    public static boolean key(Screen screen, int key) {
        if (geometry(screen) == null) return false;
        int code = GrugOverlay.keyCodeForName(Keyboard.getKeyName(key));
        if (code < 0 || isRepeat()) return false;

        ensureCreated();
        boolean ctrl = isControlDown();
        for (Overlay overlay : overlays) {
            if (GrugOverlay.key(overlay.entityHandle, overlay.keyFnId, code, ctrl)) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return false;
    }

    /** Whether control is held, or whether the test press being delivered says it is. */
    private static boolean isControlDown() {
        if (testPress) return testCtrl;
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    /** Whether the key event is the game repeating a held key, or the test press says it is. */
    private static boolean isRepeat() {
        if (testPress) return testRepeat;
        return Keyboard.isRepeatEvent();
    }

    private static boolean testPress = false;
    private static boolean testCtrl = false;
    private static boolean testRepeat = false;

    /** The key a test holds down with {@code release} false, or -1. */
    private static int testHeld = -1;

    /**
     * Test only: delivers one press of the key whose character code is {@code charCode} through the
     * screen's own key path, so the hook that cancels the key is the one under test, and answers
     * whether an overlay took it. A key held with {@code release} false makes the next press of it
     * a repeat, as the game reports one.
     */
    public static boolean pressKey(Object screen, int charCode, boolean ctrl, boolean release) {
        if (!(screen instanceof GrugScreenGeometry)) return false;
        int key = Keyboard.getKeyIndex(String.valueOf((char) charCode));
        if (key == Keyboard.KEY_NONE) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.press_screen_key: The game has no key for character code "
                            + charCode
                            + ".");
            return false;
        }
        testPress = true;
        testCtrl = ctrl;
        testRepeat = key == testHeld;
        testHeld = release ? -1 : key;
        try {
            return ((GrugScreenGeometry) screen).grug$pressKey(key);
        } finally {
            testPress = false;
        }
    }

    /** Whether an overlay may draw on this screen, and the geometry it draws through. */
    public static GrugScreenGeometry geometry(Screen screen) {
        if (!(screen instanceof InventoryMenuScreen)) return null;
        if (screen instanceof GrugScreen) return null;
        if (screen.width <= 0 || screen.height <= 0) return null;
        return (GrugScreenGeometry) screen;
    }
}
