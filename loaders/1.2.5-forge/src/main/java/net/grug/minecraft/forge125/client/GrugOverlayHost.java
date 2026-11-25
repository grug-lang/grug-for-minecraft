package net.grug.minecraft.forge125.client;

import net.grug.minecraft.forge125.GrugGuiAccess;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugOverlayGeometry;
import net.grug.minecraft.grug.GrugOverlayScripts;
import net.grug.minecraft.grug.GrugScreenBox;
import net.grug.minecraft.grug.GrugScreenItemDraw;
import net.grug.minecraft.grug.GrugScreenText;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.GrugWorldKeys;
import net.grug.minecraft.grug.GrugWorldOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.src.EntityLiving;
import net.minecraft.src.EntityRenderer;
import net.minecraft.src.Gui;
import net.minecraft.src.GuiContainer;
import net.minecraft.src.GuiIngame;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.ModLoader;
import net.minecraft.src.RenderHelper;
import net.minecraft.src.RenderItem;
import net.minecraft.src.ScaledResolution;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
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

    /** The item renderer, made once because the game draws every icon through it. */
    private static RenderItem renderItem = null;

    /** The mouse position of the last real event, which the render pass reports while it hovers. */
    private static int mouseX = 0;

    private static int mouseY = 0;

    @GrugGenerated("utility class: never instantiated")
    private GrugOverlayHost() {}

    /**
     * The overlay files to ask, by clean name, with the mod each belongs to, and the mark that this
     * loader draws overlays at all, which a test asks before it opens a screen.
     */
    public static void init(Map<String, Long> files, Map<String, String> mods) {
        GrugOverlayScripts.init(files, mods);
        installRuntimeHooks();
        GrugOverlay.noteSupported();
        GrugWorldOverlay.noteSupported();
        GrugWorldOverlay.noteTrianglesSupported();
    }

    /**
     * The overlays to ask: one entity per overlay file, made again when the engine compiles that
     * file anew, so an edited overlay script takes effect without a restart. See #881.
     */
    private static List<GrugOverlayScripts.Script> overlays() {
        return GrugOverlayScripts.scripts();
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
            EntityRenderer.class
                    .getField("grugWorldHook")
                    .set(null, (DoubleConsumer) GrugOverlayHost::drawWorld);
            GuiIngame.class.getField("grugHudHook").set(null, (Runnable) GrugOverlayHost::drawHud);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // The runtime jar is built with the patch, so this only says the launch is not the one
            // the loader was built for; fail rather than silently leaving the overlay without a
            // mouse or screen-open hook.
            throw new IllegalStateException("The 1.2.5 runtime is missing the grug GUI hooks.", e);
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

        // The first frame is where the screen-open hook's clearing has to show: a joined client
        // draws frames between a server's open and its read, so the mark alone cannot tell a hook
        // that ran before this frame from one that ran after it (#1391).
        GrugOverlay.noteFrame(container);

        readMouse(minecraft, container);

        if (renderItem == null) {
            renderItem = new RenderItem();
        }

        String testMod = GrugTestState.currentTestMod;
        for (GrugOverlayScripts.Script overlay : overlays()) {
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

            if (!geometry.items().isEmpty()
                    || !geometry.boxes().isEmpty()
                    || !geometry.texts().isEmpty()) {
                if (overlay.mod.equals(testMod)) {
                    GrugOverlay.noteDrawn();
                }
            }

            // The item draw scales its model by ten and shades it with the game's fixed-function
            // lights, so a normalizing capability the frame left on renders each icon's left face
            // about 1.9 times brighter. The game's slime renderer turns GL_NORMALIZE on and never
            // turns it off again, so a slime rendered earlier in the session decides it. That is
            // what made the JEI item list capture flake (#1137). Pin both capabilities off for the
            // icons and put back what the frame had, so this draw is the same whatever ran before.
            // The frame's state goes back in a finally, so a draw that throws cannot leave either
            // capability, or the lighting, off for the rest of the session (#1144). The lighting is
            // turned on inside the try that turns it off, so a throw from turning it on is put back
            // the same way.
            boolean normalized = GL11.glIsEnabled(GL11.GL_NORMALIZE);
            boolean rescaled = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
            GL11.glDisable(GL11.GL_NORMALIZE);
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            try {
                try {
                    // Inside the try whose finally turns it off, so a throw from turning it on is
                    // put back too rather than leaving the GUI lighting off for the session.
                    RenderHelper.enableGUIStandardItemLighting();
                    GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                    // The item draw nests its own level, which a throw would otherwise leave at
                    // 200 for the rest of the session.
                    renderItem.zLevel = 200.0F;
                    try {
                        for (GrugScreenItemDraw item : geometry.items()) {
                            renderItem.renderItemIntoGUI(
                                    minecraft.fontRenderer,
                                    minecraft.renderEngine,
                                    new ItemStack((Item) item.item()),
                                    (int) item.x(),
                                    (int) item.y());
                        }
                    } finally {
                        renderItem.zLevel = 0.0F;
                    }
                } finally {
                    RenderHelper.disableStandardItemLighting();
                }
            } finally {
                if (normalized) GL11.glEnable(GL11.GL_NORMALIZE);
                if (rescaled) GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            }
            drawTexts(minecraft, geometry.texts());
            for (GrugScreenBox box : geometry.boxes()) {
                Gui.drawRect(
                        (int) box.x1(), (int) box.y1(), (int) box.x2(), (int) box.y2(), HIGHLIGHT);
            }
        }
    }

    /**
     * Draws an overlay's texts with the game's own font and shadow, over its icons. The font draw
     * needs textures, blending and no lighting, which the frame's last element may have left
     * otherwise, so the state it pins is put back.
     */
    private static void drawTexts(Minecraft minecraft, List<GrugScreenText> texts) {
        if (texts.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            for (GrugScreenText text : texts) {
                minecraft.fontRenderer.drawStringWithShadow(
                        text.text(), (int) text.x(), (int) text.y(), 0xFF000000 | text.rgb());
            }
        } finally {
            GL11.glPopAttrib();
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
     * answer for {@code TestTickContext.scroll_screen}. The delta is in notches, positive up.
     */
    public static boolean scroll(GuiScreen screen, int x, int y, double delta) {
        GuiContainer container = eligible(screen);
        if (container == null) return false;

        for (GrugOverlayScripts.Script overlay : overlays()) {
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
     * {@code TestTickContext.click_screen}.
     */
    public static boolean click(GuiScreen screen, int x, int y, int button, boolean shift) {
        GuiContainer container = eligible(screen);
        if (container == null) return false;

        for (GrugOverlayScripts.Script overlay : overlays()) {
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
        return key(
                (GuiScreen) screenObj,
                Keyboard.getEventCharacter(),
                Keyboard.getEventKey(),
                ctrl,
                Keyboard.isRepeatEvent());
    }

    /**
     * Asks the overlays about one key press or typed character and answers whether one took it,
     * recording the answer for {@code TestTickContext.press_screen_key}.
     *
     * <p>The key is the number {@code OverlayKeyContext.key} documents and the character is the one
     * the game produced, with shift applied, so a letter arrives as both and a punctuation key as a
     * character only. A press the game reports again while its key is held is delivered with {@code
     * OverlayKeyContext.repeat} true, so a toggle flips once per press and a text field types its
     * character again.
     */
    public static boolean key(
            GuiScreen screen, char character, int key, boolean ctrl, boolean repeat) {
        if (eligible(screen) == null) return false;
        int code = GrugOverlay.keyCodeForName(Keyboard.getKeyName(key));
        if (code < 0) code = 0;
        int typed = GrugOverlay.characterCode(character);
        if (code == 0 && typed == 0) return false;

        for (GrugOverlayScripts.Script overlay : overlays()) {
            if (GrugOverlay.key(overlay.entityHandle, overlay.keyFnId, code, typed, ctrl, repeat)) {
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
     * overlays and answers whether one took it. The press types the character that number produces
     * without shift, as a real press does, and a number that names no key is only a character. A
     * number held with {@code release} false makes the next press of it a repeat, as the game
     * reports one.
     *
     * <p>Unlike a mouse press, a key press is not put into LWJGL's queue: the keyboard state that
     * says whether control is held cannot be written, so the decoded event goes to {@link #key}
     * directly, the same call the patched input loop makes.
     */
    public static boolean pressKey(GuiScreen screen, int charCode, boolean ctrl, boolean release) {
        int key = Keyboard.getKeyIndex(GrugOverlay.keyNameForCode(charCode));
        int typed = ctrl ? 0 : GrugOverlay.typedCharacterFor(charCode);
        if (key == Keyboard.KEY_NONE && typed == 0) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "TestTickContext.press_screen_key: The game has no key for character code "
                            + charCode
                            + ".");
            return false;
        }
        boolean repeat = charCode == testHeld;
        testHeld = release ? -1 : charCode;
        GrugOverlay.beginTestPress();
        key(screen, (char) typed, key, ctrl, repeat);
        return GrugOverlay.testPressTaken();
    }

    /**
     * Test only: puts one press into the game's own mouse queue and runs the screen's input loop.
     *
     * <p>This is the same patched loop a real press goes through, so it tests the input patch as
     * well as the overlay: a press an overlay takes must be skipped by {@code handleMouseInput},
     * and a press no overlay takes must still reach it.
     *
     * <p>A grug mod's own {@link GrugScreen} is not offered to the overlays, as {@link #eligible}
     * says, but a press on it still has to reach the screen: its container is the server's, so a
     * press is how a GUI test moves an item in it (#1094). The press is delivered and no overlay is
     * asked, which is the order a real press on that screen has too.
     */
    public static boolean clickThroughScreen(GuiScreen screen, int x, int y, int button) {
        if (!(screen instanceof GuiContainer)) return false;
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
                        "TestTickContext.click_screen: the mouse is grabbed, so a screen press"
                                + " cannot be synthesized.");
            }
            int start = buffer.limit();
            int readPosition = buffer.position();
            if (buffer.capacity() - start < Mouse.EVENT_SIZE) {
                throw new IllegalStateException(
                        "TestTickContext.click_screen: LWJGL's mouse event buffer is full.");
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
                    "TestTickContext.click_screen: LWJGL's mouse event buffer cannot be reached.",
                    e);
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

    private static final GrugWorldKeys worldKeys = new GrugWorldKeys();

    /**
     * Whether the player has no screen open. A headless client is never the active window, so this
     * loader keeps a {@link GrugEmptyScreen} up instead of null (see its comment), and it counts as
     * no screen: the world is in view and the HUD is drawn under it.
     */
    private static boolean noScreen(Minecraft minecraft) {
        return minecraft.currentScreen == null
                || minecraft.currentScreen instanceof GrugEmptyScreen;
    }

    /**
     * Draws every overlay's HUD export after the game's own HUD, in the GUI's scaled pixels, while
     * no screen is open and F1 has not hidden the HUD. The patched {@code
     * GuiIngame.renderGameOverlay} calls it at each return, with the GUI's projection still set.
     */
    public static void drawHud() {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        if (minecraft.theWorld == null || !noScreen(minecraft) || minecraft.gameSettings.hideGUI) {
            return;
        }
        ScaledResolution resolution =
                new ScaledResolution(
                        minecraft.gameSettings, minecraft.displayWidth, minecraft.displayHeight);
        String testMod = GrugTestState.currentTestMod;
        // The game's HUD leaves lighting, the depth test and the blend state as its last element
        // wanted them, so the text pins what a glyph needs and puts the frame's state back.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            for (GrugOverlayScripts.Script overlay : overlays()) {
                List<GrugWorldOverlay.Text> texts =
                        GrugWorldOverlay.hud(
                                overlay.entityHandle,
                                overlay.hudFnId,
                                resolution.getScaledWidth(),
                                resolution.getScaledHeight());
                for (GrugWorldOverlay.Text text : texts) {
                    minecraft.fontRenderer.drawStringWithShadow(
                            text.text(), (int) text.x(), (int) text.y(), 0xFF000000 | text.rgb());
                }
                if (!texts.isEmpty() && overlay.mod.equals(testMod)) {
                    GrugWorldOverlay.noteDrawn();
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * Draws every overlay's world export as lines, depth-tested against the level. The patched
     * {@code EntityRenderer.renderRainSnow} calls it with the partial tick, after the level and its
     * entities and before the clouds and the hand, with the camera's model-view matrix set, so the
     * vertices are camera-relative.
     */
    public static void drawWorld(double partialTick) {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        EntityLiving view = minecraft.renderViewEntity;
        if (minecraft.theWorld == null || view == null) return;
        double cameraX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTick;
        double cameraY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTick;
        double cameraZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTick;
        String testMod = GrugTestState.currentTestMod;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            List<GrugWorldOverlay.Line> lines =
                    GrugWorldOverlay.world(
                            overlay.entityHandle,
                            overlay.worldFnId,
                            partialTick,
                            cameraX,
                            cameraY,
                            cameraZ);
            List<GrugWorldOverlay.Triangle> triangles = GrugWorldOverlay.lastTriangles();
            if (lines.isEmpty() && triangles.isEmpty()) continue;
            if (overlay.mod.equals(testMod)) {
                GrugWorldOverlay.noteDrawn();
            }
            boolean texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            boolean fog = GL11.glIsEnabled(GL11.GL_FOG);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int blendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
            int blendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glLineWidth(2.0F);
            try {
                GL11.glBegin(GL11.GL_LINES);
                for (GrugWorldOverlay.Line line : lines) {
                    int rgb = line.rgb();
                    GL11.glColor4f(
                            ((rgb >> 16) & 0xFF) / 255.0F,
                            ((rgb >> 8) & 0xFF) / 255.0F,
                            (rgb & 0xFF) / 255.0F,
                            1.0F);
                    GL11.glVertex3f(
                            (float) (line.x1() - cameraX),
                            (float) (line.y1() - cameraY),
                            (float) (line.z1() - cameraZ));
                    GL11.glVertex3f(
                            (float) (line.x2() - cameraX),
                            (float) (line.y2() - cameraY),
                            (float) (line.z2() - cameraZ));
                }
                GL11.glEnd();
                drawTriangles(triangles, cameraX, cameraY, cameraZ);
            } finally {
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                GL11.glLineWidth(1.0F);
                GL11.glBlendFunc(blendSrc, blendDst);
                GL11.glDepthMask(depthMask);
                if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
                if (blend) {
                    GL11.glEnable(GL11.GL_BLEND);
                } else {
                    GL11.glDisable(GL11.GL_BLEND);
                }
                if (fog) GL11.glEnable(GL11.GL_FOG);
                if (lighting) GL11.glEnable(GL11.GL_LIGHTING);
                if (texture) GL11.glEnable(GL11.GL_TEXTURE_2D);
            }
        }
    }

    /**
     * Draws the world pass's translucent triangles after its lines. Blending is on and the depth
     * mask is off, so the terrain in front still hides a triangle but two translucent shells do not
     * hide each other and their order does not matter, and culling is off so the inside of a closed
     * shape is drawn when the camera is in it.
     */
    private static void drawTriangles(
            List<GrugWorldOverlay.Triangle> triangles,
            double cameraX,
            double cameraY,
            double cameraZ) {
        if (triangles.isEmpty()) return;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (GrugWorldOverlay.Triangle triangle : triangles) {
            int argb = triangle.argb();
            GL11.glColor4f(
                    ((argb >> 16) & 0xFF) / 255.0F,
                    ((argb >> 8) & 0xFF) / 255.0F,
                    (argb & 0xFF) / 255.0F,
                    ((argb >>> 24) & 0xFF) / 255.0F);
            for (int corner = 0; corner < 3; corner++) {
                GL11.glVertex3f(
                        (float) (triangle.corner(corner, 0) - cameraX),
                        (float) (triangle.corner(corner, 1) - cameraY),
                        (float) (triangle.corner(corner, 2) - cameraZ));
            }
        }
        GL11.glEnd();
    }

    /**
     * Polls the letter and digit keys once per client tick and asks the overlays' world_key export
     * about each fresh press made with no screen open. A key held across ticks, or through a
     * screen, fires once at most.
     */
    public static void pollWorldKeys(Minecraft minecraft) {
        // The state is recorded on every poll, with no world loaded too, so a key still held when a
        // world loads is not taken for a fresh press; only the delivery waits for a world.
        boolean open = noScreen(minecraft) && minecraft.theWorld != null;
        for (int code = '0'; code <= 'Z'; code++) {
            if (!GrugWorldKeys.tracked(code)) continue;
            int key = Keyboard.getKeyIndex(String.valueOf((char) code));
            boolean down = key != Keyboard.KEY_NONE && Keyboard.isKeyDown(key);
            if (worldKeys.deliver(code, down, open)) {
                deliverWorldKey(
                        code,
                        Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                                || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
            }
        }
    }

    private static void deliverWorldKey(int code, boolean ctrl) {
        for (GrugOverlayScripts.Script overlay : overlays()) {
            GrugWorldOverlay.key(overlay.entityHandle, overlay.worldKeyFnId, code, ctrl);
        }
    }

    /** Test only: delivers one press of the key whose character code is {@code code}. */
    public static void pressWorldKey(int code, boolean ctrl) {
        GrugWorldOverlay.clearDrawn();
        deliverWorldKey(code, ctrl);
    }
}
