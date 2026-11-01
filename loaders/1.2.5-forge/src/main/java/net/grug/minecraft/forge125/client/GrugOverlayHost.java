package net.grug.minecraft.forge125.client;

import net.grug.minecraft.forge125.GrugGuiAccess;
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
import net.minecraft.src.Gui;
import net.minecraft.src.GuiContainer;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.ModLoader;
import net.minecraft.src.RenderHelper;
import net.minecraft.src.RenderItem;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Asks the loaded overlays to draw on the player's container screens and to take their clicks on
 * 1.2.5.
 *
 * <p>1.2.5 has no screen event bus, so this loader hooks the frame from its tick handler and the
 * mouse from two fields the build adds to {@code GuiScreen}: {@link GrugClientHooks} draws the
 * recorded icons and boxes just after the screen has drawn, and the patched input loop asks this
 * class about one event before the vanilla screen sees it. That is the same order the reference's
 * overlay gets, without an event the version does not have.
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
        final long keyFnId;
        final long scrollFnId;
        final String mod;

        Overlay(
                long entityHandle,
                List<GrugObject> owner,
                long renderFnId,
                long clickFnId,
                long keyFnId,
                long scrollFnId,
                String mod) {
            this.entityHandle = entityHandle;
            this.owner = owner;
            this.renderFnId = renderFnId;
            this.clickFnId = clickFnId;
            this.keyFnId = keyFnId;
            this.scrollFnId = scrollFnId;
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
        installRuntimeHooks();
        GrugOverlay.noteSupported();
    }

    /**
     * Hands this loader's handlers to the hooks the runtime jar carries.
     *
     * <p>1.2.5's game classes are loaded by launchwrapper's parent and cannot see a mod's classes,
     * so the build patches {@code GuiScreen} to hold these three fields and to call them from the
     * input loop and from {@code Minecraft.displayGuiScreen}. The fields are on the game's own
     * class, so the loader reaches them through it; see {@code tools/PatchGuiInput.java}.
     */
    @SuppressWarnings("unchecked")
    private static void installRuntimeHooks() {
        try {
            Field mouse = GuiScreen.class.getField("grugMouseHook");
            mouse.set(null, (Predicate<Object>) GrugOverlayHost::handleMouseEvent);
            Field key = GuiScreen.class.getField("grugKeyHook");
            key.set(null, (Predicate<Object>) GrugOverlayHost::handleKeyEvent);
            Field open = GuiScreen.class.getField("grugScreenOpenHook");
            open.set(null, (Consumer<Object>) GrugOverlayHost::screenOpened);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // The runtime jar is built with the patch, so this only says the launch is not the one
            // the loader was built for; fail rather than silently leaving the overlay without a
            // mouse or screen-open hook.
            throw new IllegalStateException("The 1.2.5 runtime is missing the grug GUI hooks.", e);
        }
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
                            Grug.getExportFnId("Overlay", "scroll"),
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
        if (container == null) return;

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
     * Clears the drawn mark when a screen opens or closes, before the screen's first frame.
     *
     * <p>The patched {@code Minecraft.displayGuiScreen} calls this for every screen it assigns.
     * Clearing here rather than on the render tick means a test cannot read the previous screen's
     * mark while the new screen waits for its first frame.
     */
    public static void screenOpened(Object screen) {
        GrugOverlay.screenOpened();
    }

    /**
     * Routes one real mouse event through the overlays before the screen sees it.
     *
     * <p>The patched {@code GuiScreen.handleInput} calls this after {@code Mouse.next()} has
     * selected the event and before the screen's own handler runs. A press an overlay takes is
     * consumed by returning true, so the vanilla slot under it never sees it. Every other event,
     * including a press no overlay took, returns false and is then handled by the screen exactly as
     * vanilla would have handled it: the wheel, key state and release events are not removed from
     * the game's input pass.
     */
    public static boolean handleMouseEvent(Object screenObj) {
        GuiScreen screen = (GuiScreen) screenObj;
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        GuiContainer container = eligible(screen);
        if (container == null || minecraft == null) return false;

        int x = Mouse.getEventX() * container.width / minecraft.displayWidth;
        int y =
                container.height
                        - Mouse.getEventY() * container.height / minecraft.displayHeight
                        - 1;
        mouseX = x;
        mouseY = y;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) return scroll(container, x, y, wheel / WHEEL_NOTCH);
        if (!Mouse.getEventButtonState()) return false;
        return click(container, x, y, Mouse.getEventButton(), GuiScreen.isShiftKeyDown());
    }

    /** LWJGL 2's wheel delta for one notch, which turns the delta into the API's notches. */
    private static final double WHEEL_NOTCH = 120.0;

    /**
     * Asks the overlays about one wheel movement and answers whether one took it, recording the
     * answer for {@code Test.scroll_screen}. The delta is in notches, positive up.
     */
    public static boolean scroll(GuiScreen screen, int x, int y, double delta) {
        GuiContainer container = eligible(screen);
        if (container == null) return false;

        ensureCreated();
        for (Overlay overlay : overlays) {
            boolean taken =
                    GrugOverlay.scroll(
                            overlay.entityHandle,
                            overlay.scrollFnId,
                            container.width,
                            container.height,
                            GrugGuiAccess.guiLeft(container),
                            GrugGuiAccess.guiTop(container),
                            GrugGuiAccess.xSize(container),
                            GrugGuiAccess.ySize(container),
                            x,
                            y,
                            delta);
            if (taken) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return false;
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

    /**
     * Routes one real key event through the overlays before the screen sees it.
     *
     * <p>The patched {@code GuiScreen.handleInput} calls this after {@code Keyboard.next()} has
     * selected the event and before the screen's own handler runs. A key an overlay takes is
     * consumed by returning true, so the screen never sees it; every other event, a release
     * included, returns false and is handled as vanilla would.
     */
    public static boolean handleKeyEvent(Object screenObj) {
        if (!Keyboard.getEventKeyState()) return false;
        boolean ctrl =
                Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                        || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        return key((GuiScreen) screenObj, Keyboard.getEventKey(), ctrl, Keyboard.isRepeatEvent());
    }

    /**
     * Asks the overlays about one key press and answers whether one took it, recording the answer
     * for {@code Test.press_screen_key}.
     *
     * <p>Only a letter or a digit key is asked, given as its upper case character code, which is
     * the same number on every loader. A key the game reports again while it is held is a repeat
     * and is not asked, so a toggle flips once per press.
     */
    public static boolean key(GuiScreen screen, int key, boolean ctrl, boolean repeat) {
        if (eligible(screen) == null) return false;
        int code = GrugOverlay.keyCodeForName(Keyboard.getKeyName(key));
        if (code < 0 || repeat) return false;

        ensureCreated();
        for (Overlay overlay : overlays) {
            if (GrugOverlay.key(overlay.entityHandle, overlay.keyFnId, code, ctrl)) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return false;
    }

    /** The key a test holds down with {@code release} false, or -1. */
    private static int testHeld = -1;

    /**
     * Test only: delivers one press of the key whose character code is {@code charCode} to the
     * overlays and answers whether one took it. A key held with {@code release} false makes the
     * next press of it a repeat, as the game reports one.
     *
     * <p>Unlike a mouse press, a key press is not put into LWJGL's queue: the keyboard state that
     * says whether control is held cannot be written, so the decoded event goes to {@link #key}
     * directly, the same call the patched input loop makes.
     */
    public static boolean pressKey(GuiScreen screen, int charCode, boolean ctrl, boolean release) {
        int key = Keyboard.getKeyIndex(String.valueOf((char) charCode));
        if (key == Keyboard.KEY_NONE) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.press_screen_key: The game has no key for character code "
                            + charCode
                            + ".");
            return false;
        }
        boolean repeat = key == testHeld;
        testHeld = release ? -1 : key;
        GrugOverlay.beginTestPress();
        key(screen, key, ctrl, repeat);
        return GrugOverlay.testPressTaken();
    }

    /**
     * Test only: puts one press into the game's own mouse queue and runs the screen's input loop.
     *
     * <p>This is the same patched loop a real press goes through, so it tests the input patch as
     * well as the overlay: a press an overlay takes must be skipped by {@code handleMouseInput},
     * and a press no overlay takes must still reach it.
     */
    public static boolean clickThroughScreen(GuiScreen screen, int x, int y, int button) {
        if (eligible(screen) == null) return false;
        GrugOverlay.beginTestPress();
        appendMouseEvent(screen, x, y, button, true, 0);
        screen.handleInput();
        return GrugOverlay.testPressTaken();
    }

    /**
     * Test only: puts one wheel movement into the game's own mouse queue and runs the screen's
     * input loop, the same patched loop a real wheel goes through.
     */
    public static boolean scrollThroughScreen(GuiScreen screen, int x, int y, double delta) {
        if (eligible(screen) == null) return false;
        GrugOverlay.beginTestPress();
        appendMouseEvent(screen, x, y, -1, false, (int) Math.round(delta * WHEEL_NOTCH));
        screen.handleInput();
        return GrugOverlay.testPressTaken();
    }

    /**
     * Appends one button event to LWJGL's read buffer, where the game's next input pass finds it.
     *
     * <p>LWJGL 2 has no public way to post a mouse event. The buffer is filled by {@code
     * Mouse.poll} and consumed by {@code Mouse.next}; adding an event at its limit before the
     * game's loop runs is the same bytes the native path would have written. The event has two
     * bytes, the absolute x and y, the wheel delta and the timestamp, because the screen has
     * ungrabbed the mouse.
     */
    private static void appendMouseEvent(
            GuiScreen screen, int x, int y, int button, boolean state, int wheel) {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        int rawX = x * minecraft.displayWidth / screen.width;
        int rawY = (screen.height - y - 1) * minecraft.displayHeight / screen.height;
        try {
            Field field = Mouse.class.getDeclaredField("readBuffer");
            field.setAccessible(true);
            ByteBuffer buffer = (ByteBuffer) field.get(null);
            if (Mouse.isGrabbed()) {
                throw new IllegalStateException(
                        "Test.click_screen: the mouse is grabbed, so a screen press cannot be"
                                + " synthesized.");
            }
            int start = buffer.limit();
            int readPosition = buffer.position();
            if (buffer.capacity() - start < Mouse.EVENT_SIZE) {
                throw new IllegalStateException(
                        "Test.click_screen: LWJGL's mouse event buffer is full.");
            }
            buffer.limit(buffer.capacity());
            buffer.position(start);
            buffer.put((byte) button);
            buffer.put((byte) (state ? 1 : 0));
            buffer.putInt(rawX);
            buffer.putInt(rawY);
            buffer.putInt(wheel);
            buffer.putLong(System.nanoTime());
            buffer.limit(buffer.position());
            buffer.position(readPosition);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Test.click_screen: LWJGL's mouse event buffer cannot be reached.", e);
        }
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
