package net.grug.minecraft.ornithe.client;

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
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.entity.ItemRenderer;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Asks the loaded overlays to draw on the player's container screens and to take their clicks on
 * Beta 1.7.3 Ornithe.
 *
 * <p>The screen hooks live in the shared {@code InventoryMenuScreenMixin}, because this version has
 * no screen event a mod can subscribe to. Core makes and drops the overlay entities; this class is
 * the half that asks them and turns a pass's recorded geometry into the game's own item drawing.
 */
public final class GrugOverlayHost {

    private static ItemRenderer renderItem = null;

    @GrugGenerated("utility class: never instantiated")
    private GrugOverlayHost() {}

    /**
     * The overlay files to ask, by clean name, with the mod each belongs to, and the mark that this
     * loader draws overlays at all, which a test asks before it opens a screen.
     */
    public static void init(Map<String, Long> files, Map<String, String> mods) {
        GrugOverlayScripts.init(files, mods);
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
     * Draws every overlay on top of the screen's own frame and answers the boxes the caller still
     * has to fill.
     *
     * <p>The icons are drawn here because item drawing belongs to the game's renderer; the
     * highlight boxes go back to the mixin, which is the only place with the screen's own fill.
     */
    public static List<GrugScreenBox> render(Screen screen, int mouseX, int mouseY) {
        GrugScreenGeometry geometry = geometry(screen);
        if (geometry == null) return Collections.emptyList();

        // The first frame is where the screen-open hook's clearing has to show: a joined client
        // draws frames between a server's open and its read, so the mark alone cannot tell a hook
        // that ran before this frame from one that ran after it (#1391).
        GrugOverlay.noteFrame(screen);

        if (renderItem == null) {
            renderItem = new ItemRenderer();
        }

        Minecraft minecraft = MinecraftInstance.get();
        String testMod = GrugTestState.currentTestMod;
        List<GrugScreenBox> boxes = new ArrayList<>();
        for (GrugOverlayScripts.Script overlay : overlays()) {
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

            if (!drawn.items().isEmpty() || !drawn.boxes().isEmpty() || !drawn.texts().isEmpty()) {
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
                    Lighting.turnOn();
                    for (GrugScreenItemDraw item : drawn.items()) {
                        renderItem.renderGuiItemWithEnchantmentGlint(
                                minecraft.textRenderer,
                                minecraft.textureManager,
                                new ItemStack((Item) item.item()),
                                (int) item.x(),
                                (int) item.y());
                    }
                } finally {
                    Lighting.turnOff();
                }
            } finally {
                if (normalized) GL11.glEnable(GL11.GL_NORMALIZE);
                if (rescaled) GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            }
            drawTexts(minecraft, drawn.texts());
            boxes.addAll(drawn.boxes());
        }
        return boxes;
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
                minecraft.textRenderer.drawWithShadow(
                        text.text(), (int) text.x(), (int) text.y(), text.rgb());
            }
        } finally {
            GL11.glPopAttrib();
        }
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
     * {@code TestTickContext.click_screen}.
     */
    public static boolean click(Screen screen, int x, int y, int button, boolean shift) {
        GrugScreenGeometry geometry = geometry(screen);
        if (geometry == null) return false;

        for (GrugOverlayScripts.Script overlay : overlays()) {
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
     * for {@code TestTickContext.press_screen_key}.
     *
     * <p>Only a letter, a digit, the space bar and backspace are asked, given as the upper case
     * character code or the ASCII code, which is the same number on every loader. A key the game
     * reports again while it is held is a repeat and is not asked, so a toggle flips once per
     * press.
     */
    public static boolean key(Screen screen, int key) {
        if (geometry(screen) == null) return false;
        int code = GrugOverlay.keyCodeForName(Keyboard.getKeyName(key));
        if (code < 0) return false;
        if (isRepeat()) return false;

        boolean ctrl = isControlDown();
        for (GrugOverlayScripts.Script overlay : overlays()) {
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
        int key = Keyboard.getKeyIndex(GrugOverlay.keyNameForCode(charCode));
        if (key == Keyboard.KEY_NONE) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "TestTickContext.press_screen_key: The game has no key for character code "
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

    /** LWJGL 2's wheel delta for one notch, which turns the delta into the API's notches. */
    private static final double WHEEL_NOTCH = 120.0;

    private static boolean testScroll = false;
    private static int testX = 0;
    private static int testY = 0;
    private static double testDelta = 0.0;

    /**
     * Asks the overlays about the wheel movement the screen's mouse handler is looking at and
     * answers whether the screen's handler must be skipped, recording whether one took it for
     * {@code TestTickContext.scroll_screen}.
     *
     * <p>The screen's mouse handler runs for every mouse event, a press and a release included, so
     * an event with no wheel movement is declined first. The delta is in notches, positive up, and
     * the position is worked out the way the screen's own handler works out a press. A test scroll
     * always skips the handler, taken or not: the handler would otherwise read LWJGL's stale
     * current event, which could be a button press, and replay it.
     */
    public static boolean wheel(Screen screen, int displayWidth, int displayHeight) {
        double delta = testScroll ? testDelta : Mouse.getEventDWheel() / WHEEL_NOTCH;
        if (delta == 0.0) return testScroll;
        GrugScreenGeometry geometry = geometry(screen);
        if (geometry == null) return testScroll;

        int x = testScroll ? testX : Mouse.getEventX() * screen.width / displayWidth;
        int y =
                testScroll
                        ? testY
                        : screen.height - Mouse.getEventY() * screen.height / displayHeight - 1;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            boolean taken =
                    GrugOverlay.scroll(
                            overlay.entityHandle,
                            overlay.scrollFnId,
                            geometry.grug$width(),
                            geometry.grug$height(),
                            geometry.grug$guiLeft(),
                            geometry.grug$guiTop(),
                            geometry.grug$guiWidth(),
                            geometry.grug$guiHeight(),
                            x,
                            y,
                            delta);
            if (taken) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return testScroll;
    }

    /**
     * Test only: delivers one wheel movement at a position through the screen's own mouse handler,
     * so the hook that cancels the movement is the one under test, and answers whether an overlay
     * took it.
     */
    public static boolean scrollThroughScreen(Object screen, int x, int y, double delta) {
        if (!(screen instanceof GrugScreenGeometry)) return false;
        testScroll = true;
        testX = x;
        testY = y;
        testDelta = delta;
        try {
            return ((GrugScreenGeometry) screen).grug$scroll();
        } finally {
            testScroll = false;
        }
    }

    private static final GrugWorldKeys worldKeys = new GrugWorldKeys();

    /**
     * Draws every overlay's HUD export on top of the finished frame, in the GUI's own scaled
     * coordinates, while no screen is open. The caller has already checked there is a world.
     */
    public static void drawHud(Minecraft minecraft) {
        // F1 hides the game's own HUD, and a HUD overlay goes with it.
        if (minecraft.options.hideGui) return;
        if (overlays().isEmpty()) return;
        Window window = new Window(minecraft.options, minecraft.width, minecraft.height);
        String testMod = GrugTestState.currentTestMod;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            List<GrugWorldOverlay.Text> texts =
                    GrugWorldOverlay.hud(
                            overlay.entityHandle,
                            overlay.hudFnId,
                            window.getWidth(),
                            window.getHeight());
            for (GrugWorldOverlay.Text text : texts) {
                minecraft.textRenderer.drawWithShadow(
                        text.text(), (int) text.x(), (int) text.y(), text.rgb());
            }
            if (!texts.isEmpty() && overlay.mod.equals(testMod)) {
                GrugWorldOverlay.noteDrawn();
            }
        }
    }

    /**
     * Draws every overlay's world export as GL lines, in the game's own world pass, so the depth
     * buffer hides a line behind a block in front of it. Vertices are camera-relative, as the
     * game's own world draws are. F1 does not hide it: SafeSquares' marks are world geometry and
     * stay. With anaglyph 3D the game renders the world twice, so this runs twice per frame.
     */
    public static void drawWorld(Minecraft minecraft, float partialTick) {
        MobEntity camera = minecraft.camera;
        if (camera == null) return;
        if (overlays().isEmpty()) return;
        double cx = camera.prevX + (camera.x - camera.prevX) * partialTick;
        double cy = camera.prevY + (camera.y - camera.prevY) * partialTick;
        double cz = camera.prevZ + (camera.z - camera.prevZ) * partialTick;
        String testMod = GrugTestState.currentTestMod;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            List<GrugWorldOverlay.Line> lines =
                    GrugWorldOverlay.world(
                            overlay.entityHandle, overlay.worldFnId, partialTick, cx, cy, cz);
            List<GrugWorldOverlay.Triangle> triangles = GrugWorldOverlay.lastTriangles();
            if (lines.isEmpty() && triangles.isEmpty()) continue;
            if (overlay.mod.equals(testMod)) {
                GrugWorldOverlay.noteDrawn();
            }
            GL11.glPushAttrib(
                    GL11.GL_ENABLE_BIT
                            | GL11.GL_LINE_BIT
                            | GL11.GL_CURRENT_BIT
                            | GL11.GL_COLOR_BUFFER_BIT
                            | GL11.GL_DEPTH_BUFFER_BIT);
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_FOG);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glLineWidth(2.0F);
                GL11.glBegin(GL11.GL_LINES);
                for (GrugWorldOverlay.Line line : lines) {
                    int rgb = line.rgb();
                    GL11.glColor3f(
                            ((rgb >> 16) & 0xFF) / 255.0F,
                            ((rgb >> 8) & 0xFF) / 255.0F,
                            (rgb & 0xFF) / 255.0F);
                    GL11.glVertex3d(line.x1() - cx, line.y1() - cy, line.z1() - cz);
                    GL11.glVertex3d(line.x2() - cx, line.y2() - cy, line.z2() - cz);
                }
                GL11.glEnd();
                drawTriangles(triangles, cx, cy, cz);
            } finally {
                GL11.glPopAttrib();
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
            List<GrugWorldOverlay.Triangle> triangles, double cx, double cy, double cz) {
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
                GL11.glVertex3d(
                        triangle.corner(corner, 0) - cx,
                        triangle.corner(corner, 1) - cy,
                        triangle.corner(corner, 2) - cz);
            }
        }
        GL11.glEnd();
    }

    /**
     * Polls the letter and digit keys once per client tick while no screen is open and asks the
     * overlays' world_key export about each fresh press. A key held across ticks fires once.
     */
    public static void pollWorldKeys(Minecraft minecraft) {
        // The state is recorded on every poll, with no world loaded too, so a key still held when a
        // world loads is not taken for a fresh press; only the delivery waits for a world.
        boolean open = minecraft.screen == null && minecraft.world != null;
        for (int code = '0'; code <= 'Z'; code++) {
            if (!GrugWorldKeys.tracked(code)) continue;
            int key = Keyboard.getKeyIndex(String.valueOf((char) code));
            boolean down = key != Keyboard.KEY_NONE && Keyboard.isKeyDown(key);
            if (worldKeys.deliver(code, down, open)) {
                deliverWorldKey(code, isControlDown());
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

    /** Whether an overlay may draw on this screen, and the geometry it draws through. */
    public static GrugScreenGeometry geometry(Screen screen) {
        if (!(screen instanceof InventoryMenuScreen)) return null;
        if (screen instanceof GrugScreen) return null;
        if (screen.width <= 0 || screen.height <= 0) return null;
        return (GrugScreenGeometry) screen;
    }
}
