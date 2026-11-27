package net.grug.minecraft.stationapi.client;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugRunWindow;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugOverlayGeometry;
import net.grug.minecraft.grug.GrugOverlayScripts;
import net.grug.minecraft.grug.GrugOwnedScreens;
import net.grug.minecraft.grug.GrugScreenBox;
import net.grug.minecraft.grug.GrugScreenItemDraw;
import net.grug.minecraft.grug.GrugScreenText;
import net.grug.minecraft.grug.GrugScreenTooltip;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.GrugWorldKeys;
import net.grug.minecraft.grug.GrugWorldOverlay;
import net.grug.minecraft.stationapi.GrugClientHooks;
import net.grug.minecraft.stationapi.gui.GrugScreen;
import net.grug.minecraft.stationapi.mixin.GrugFillInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.client.util.ScreenScaler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.List;
import java.util.Map;

/**
 * Asks the loaded overlays to draw on the player's container screens and to take their clicks on
 * Beta 1.7.3 StationAPI.
 *
 * <p>The screen hooks live in {@code HandledScreenMixin}, because this version has no screen event
 * a mod can subscribe to. Core makes and drops the overlay entities; this class is the half that
 * asks them and turns a pass's recorded geometry into the game's own item drawing.
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
     * Draws every overlay on top of the screen's own frame.
     *
     * <p>The icons are drawn here because item drawing belongs to the game's renderer, and the
     * highlight boxes and tooltips go through the screen's own fill, which the mixin makes
     * reachable, so everything an overlay recorded lands in the frame the screen just drew.
     */
    public static void render(Screen screen, int mouseX, int mouseY) {
        GrugScreenGeometry geometry = geometry(screen);
        if (geometry == null) return;

        // The first frame is where the screen-open hook's clearing has to show: a joined client
        // draws frames between a server's open and its read, so the mark alone cannot tell a hook
        // that ran before this frame from one that ran after it (#1391).
        GrugOverlay.noteFrame(screen);

        Minecraft minecraft = (Minecraft) FabricLoader.getInstance().getGameInstance();
        String testMod = GrugTestState.currentTestMod;
        // A test that parked the cursor for a capture is where the overlay must see the mouse. The
        // point is in window pixels, and the screen's scaled size turns it into the pixels an
        // overlay gets, the same way this screen works out its own mouse.
        double[] parked =
                GrugRunWindow.parkedMousePosition(
                        minecraft.displayWidth,
                        minecraft.displayHeight,
                        screen.width,
                        screen.height);
        double overlayMouseX = parked == null ? mouseX : parked[0];
        double overlayMouseY = parked == null ? mouseY : parked[1];
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
                            overlayMouseX,
                            overlayMouseY);

            if (!drawn.items().isEmpty()
                    || !drawn.boxes().isEmpty()
                    || !drawn.texts().isEmpty()
                    || !drawn.tooltips().isEmpty()) {
                if (overlay.mod.equals(testMod)) {
                    GrugOverlay.noteDrawn();
                }
            }

            drawGeometry(screen, minecraft, drawn);
        }
    }

    /**
     * Draws what one render pass recorded: the item icons, texts, highlight boxes and tooltips, in
     * the order it drew them. An overlay's pass on a container screen and a mod-owned screen's
     * {@code screen_render} both land here, so the two draws look the same and a tooltip stays over
     * everything else.
     */
    public static void drawGeometry(
            Screen screen, Minecraft minecraft, GrugOverlayGeometry geometry) {
        if (renderItem == null) {
            renderItem = new ItemRenderer();
        }
        GrugFillInvoker fill = (GrugFillInvoker) screen;
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
                for (GrugScreenItemDraw item : geometry.items()) {
                    renderItem.renderGuiItem(
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
        drawTexts(minecraft, geometry.texts());
        for (GrugScreenBox box : geometry.boxes()) {
            fill.grug$fill(
                    (int) box.x1(), (int) box.y1(), (int) box.x2(), (int) box.y2(), 0x80FFFFFF);
        }
        drawTooltips(minecraft, fill, geometry.tooltips());
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
                        text.text(), (int) text.x(), (int) text.y(), 0xFF000000 | text.rgb());
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * Draws an overlay's tooltips the way this version's own item tooltip is drawn: a flat
     * translucent panel three pixels around the text, and the line with a shadow at the anchor
     * moved 12 pixels right and 12 up, which is {@code InventoryMenuScreen.render}'s own tooltip.
     * Alpha has no item tooltip at all, so its loader uses this same panel.
     *
     * <p>The panel and the text need the state the game's tooltip has, textures and blending on and
     * lighting and the depth test off, so the panel is not hidden by the item under it. The state
     * the frame had goes back afterwards.
     */
    private static void drawTooltips(
            Minecraft minecraft, GrugFillInvoker fill, List<GrugScreenTooltip> tooltips) {
        if (tooltips.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            for (GrugScreenTooltip tooltip : tooltips) {
                int x = (int) tooltip.x() + 12;
                int y = (int) tooltip.y() - 12;
                int width = minecraft.textRenderer.getWidth(tooltip.text());
                // 0xC0000000, the game's own tooltip panel colour.
                fill.grug$fillGradient(
                        x - 3, y - 3, x + width + 3, y + 8 + 3, -1073741824, -1073741824);
                minecraft.textRenderer.drawWithShadow(tooltip.text(), x, y, -1);
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
     * Asks the overlays about one key press or typed character and answers whether one took it,
     * recording the answer for {@code TestTickContext.press_screen_key}.
     *
     * <p>The key is the number {@code OverlayKeyContext.key} documents and the character is the one
     * the game produced, with shift applied, so a letter arrives as both and a punctuation key as a
     * character only. Shift is the event's own flag, which a text field extends its selection with.
     * A press the game reports again while its key is held is delivered with {@code
     * OverlayKeyContext.repeat} true, so a toggle flips once per press and a text field types its
     * character again.
     *
     * <p>An event that types a printable character is delivered as a control chord only when
     * control is held without alt: on Windows an AltGr press carries both bits, and the character
     * it types is the keyboard layout's own, which a text field types instead of leaving it to the
     * screen (#1741).
     */
    public static boolean key(Screen screen, char character, int key) {
        if (geometry(screen) == null) return false;
        int code = GrugOverlay.keyCodeForName(Keyboard.getKeyName(key));
        if (code < 0) code = 0;
        int typed = GrugOverlay.characterCode(character);
        if (code == 0 && typed == 0) return false;

        boolean ctrl = isControlDown();
        boolean shift = isShiftDown();
        boolean repeat = isRepeat();
        boolean chord = typed > 0 ? GrugOverlay.characterEventIsChord(ctrl, isAltDown()) : ctrl;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            if (GrugOverlay.key(
                    overlay.entityHandle, overlay.keyFnId, code, typed, chord, shift, repeat)) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return false;
    }

    /** Whether control is held, or whether the test press being delivered says it is. */
    static boolean controlHeld() {
        return isControlDown();
    }

    /** Whether shift is held, or whether the test press being delivered says it is. */
    static boolean shiftHeld() {
        return isShiftDown();
    }

    /** Whether control is held, or whether the test press being delivered says it is. */
    private static boolean isControlDown() {
        if (testPress) return testCtrl;
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    /** Whether shift is held, or whether the test press being delivered says it is. */
    private static boolean isShiftDown() {
        if (testPress) return testShift;
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    /**
     * Whether alt is held, which with control on Windows is how an AltGr press is reported, as the
     * spurious left control it sends beside the right alt.
     */
    private static boolean isAltDown() {
        if (testPress) return false;
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    /** Whether the key event is the game repeating a held key, or the test press says it is. */
    private static boolean isRepeat() {
        if (testPress) return testRepeat;
        return Keyboard.isRepeatEvent();
    }

    private static boolean testPress = false;
    private static boolean testCtrl = false;
    private static boolean testRepeat = false;
    private static boolean testShift = false;

    /** The key a test holds down with {@code release} false, or -1. */
    private static int testHeld = -1;

    /**
     * Test only: delivers one press of the key whose character code is {@code charCode} through the
     * screen's own key path, so the hook that cancels the key is the one under test, and answers
     * whether an overlay took it. The press types the character that number produces, with shift
     * applied, as a real press does, and a number below 128 that names no key is only a character,
     * while a number from 128 up is one of the keys with no character of its own and is pressed as
     * that key. Shift is held for the press when {@code shift} says so. A number held with {@code
     * release} false makes the next press of it a repeat, as the game reports one.
     */
    public static boolean pressKey(
            Object screen, int charCode, boolean ctrl, boolean shift, boolean release) {
        if (!(screen instanceof GrugScreenGeometry)) return false;
        int key = keyIndex(charCode);
        int typed = ctrl ? 0 : GrugOverlay.typedCharacterFor(charCode, shift);
        if (key == Keyboard.KEY_NONE && typed == 0) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "TestTickContext.press_screen_key: The game has no key for character code "
                            + charCode
                            + ".");
            return false;
        }
        testPress = true;
        testCtrl = ctrl;
        testShift = shift;
        testRepeat = charCode == testHeld;
        testHeld = release ? -1 : charCode;
        try {
            return ((GrugScreenGeometry) screen).grug$pressKey((char) typed, key);
        } finally {
            testPress = false;
            testShift = false;
        }
    }

    /**
     * The key index this loader presses for the API's key code {@code code}, or {@link
     * Keyboard#KEY_NONE} when it has no such key: this loader's own numbering is looked up from the
     * name the code has here, so an API code never reaches a loader as an index of its own. LWJGL
     * 2's index for the letter L is not 76, which is the numpad's 5, and this is the one place in
     * this loader that difference is applied.
     */
    public static int keyIndex(int code) {
        return Keyboard.getKeyIndex(GrugOverlay.keyNameForCode(code));
    }

    /**
     * Asks the mod's {@code screen_key} for one press on a screen a mod owns, and answers whether
     * it took the press, recording the answer for {@code TestTickContext.press_screen_key}.
     *
     * <p>The key and the typed character are the same numbers {@link #key} hands an overlay, and a
     * press that names no key of its own and types no character is declined here, so the screen's
     * own handler can give the press up rather than reading it as one.
     */
    static boolean pressOwnedScreenKey(GrugOwnedScreenHost host, char character, int key) {
        int code = GrugOverlay.keyCodeForName(Keyboard.getKeyName(key));
        if (code < 0) code = 0;
        int typed = GrugOverlay.characterCode(character);
        if (code == 0 && typed == 0) return false;
        boolean ctrl = isControlDown();
        boolean shift = isShiftDown();
        boolean repeat = isRepeat();
        boolean chord = typed > 0 ? GrugOverlay.characterEventIsChord(ctrl, isAltDown()) : ctrl;
        if (GrugOwnedScreens.keyScreen(host.model(), code, typed, chord, shift, repeat)) {
            GrugOverlay.noteTestPressTaken();
            return true;
        }
        return false;
    }

    /**
     * Hands one wheel movement to the screen a mod owns and answers whether the screen's mouse
     * handler must stop instead of reading the event as a button.
     *
     * <p>The movement is the one the event carries, or the one a test is holding, in which case the
     * test's position goes with it, because LWJGL's current event then belongs to whatever ran
     * before the test. This is the same order a container screen's wheel takes: the screen's own
     * scroll path, which asks {@code screen_scroll} and then the list under the point.
     */
    static boolean wheelOverOwnedScreen(
            GrugOwnedScreenHost host, int displayWidth, int displayHeight) {
        double delta = testScroll ? testDelta : Mouse.getEventDWheel() / WHEEL_NOTCH;
        if (delta == 0.0 && !testScroll) return false;
        int x = testScroll ? testX : Mouse.getEventX() * host.width / displayWidth;
        int y =
                testScroll
                        ? testY
                        : host.height - Mouse.getEventY() * host.height / displayHeight - 1;
        host.scrollAt(x, y, delta);
        return true;
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
        if (minecraft.options.hideHud) return;
        if (overlays().isEmpty()) return;
        ScreenScaler window =
                new ScreenScaler(
                        minecraft.options, minecraft.displayWidth, minecraft.displayHeight);
        String testMod = GrugTestState.currentTestMod;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            List<GrugWorldOverlay.Text> texts =
                    GrugWorldOverlay.hud(
                            overlay.entityHandle,
                            overlay.hudFnId,
                            window.getScaledWidth(),
                            window.getScaledHeight());
            for (GrugWorldOverlay.Text text : texts) {
                minecraft.textRenderer.drawWithShadow(
                        text.text(), (int) text.x(), (int) text.y(), 0xFF000000 | text.rgb());
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
        LivingEntity camera = minecraft.camera;
        if (camera == null) return;
        if (overlays().isEmpty()) return;
        double cx = camera.lastTickX + (camera.x - camera.lastTickX) * partialTick;
        double cy = camera.lastTickY + (camera.y - camera.lastTickY) * partialTick;
        double cz = camera.lastTickZ + (camera.z - camera.lastTickZ) * partialTick;
        ClientPlayerEntity player = minecraft.player;
        double feetX = player == null ? camera.x : player.x;
        double feetY = player == null ? camera.boundingBox.minY : player.boundingBox.minY;
        double feetZ = player == null ? camera.z : player.z;
        String testMod = GrugTestState.currentTestMod;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            List<GrugWorldOverlay.Line> lines =
                    GrugWorldOverlay.world(
                            overlay.entityHandle,
                            overlay.worldFnId,
                            partialTick,
                            cx,
                            cy,
                            cz,
                            feetX,
                            feetY,
                            feetZ,
                            overlay.mod);
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
        boolean open = minecraft.currentScreen == null && minecraft.world != null;
        for (int code = '0'; code <= 'Z'; code++) {
            if (!GrugWorldKeys.tracked(code)) continue;
            int key = Keyboard.getKeyIndex(String.valueOf((char) code));
            boolean down = key != Keyboard.KEY_NONE && Keyboard.isKeyDown(key);
            if (worldKeys.deliver(code, down, open)) {
                deliverWorldKey(code, isControlDown());
            }
        }
        for (GrugClientHooks.DeclaredBinding declared : GrugClientHooks.declaredBindings()) {
            boolean down = Keyboard.isKeyDown(declared.binding.code);
            java.util.List<GrugOverlayScripts.Script> scripts =
                    GrugOverlayScripts.bindingScripts(declared.mod);
            if (scripts.isEmpty()) continue;
            GrugWorldOverlay.binding(scripts, declared.mod, declared.id, down, open);
        }
    }

    private static void deliverWorldKey(int code, boolean ctrl) {
        for (GrugOverlayScripts.Script overlay : overlays()) {
            GrugWorldOverlay.key(overlay.entityHandle, overlay.worldKeyFnId, code, ctrl);
        }
    }

    /**
     * Test only: delivers one press of the key whose character code is {@code code} to the running
     * test's mod alone, so a key another mod binds by default cannot change that mod's state
     * (#1873).
     */
    public static void pressWorldKey(int code, boolean ctrl) {
        GrugWorldOverlay.clearDrawn();
        GrugWorldOverlay.pressForTest(overlays(), GrugTestState.currentTestMod, code, ctrl);
    }

    /** Whether an overlay may draw on this screen, and the geometry it draws through. */
    public static GrugScreenGeometry geometry(Screen screen) {
        if (!(screen instanceof HandledScreen)) return null;
        if (screen instanceof GrugScreen) return null;
        if (screen.width <= 0 || screen.height <= 0) return null;
        return (GrugScreenGeometry) screen;
    }
}
