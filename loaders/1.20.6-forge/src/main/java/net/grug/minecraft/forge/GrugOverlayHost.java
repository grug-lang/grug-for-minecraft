package net.grug.minecraft.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.grug.minecraft.core.GrugRunWindow;
import net.grug.minecraft.forge.gui.GrugScreen;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugOverlayGeometry;
import net.grug.minecraft.grug.GrugOverlayScripts;
import net.grug.minecraft.grug.GrugScreenBox;
import net.grug.minecraft.grug.GrugScreenItemDraw;
import net.grug.minecraft.grug.GrugScreenText;
import net.grug.minecraft.grug.GrugScreenTooltip;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.GrugWorldKeys;
import net.grug.minecraft.grug.GrugWorldOverlay;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Map;

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

    @GrugGenerated("utility class: never instantiated")
    private GrugOverlayHost() {}

    /**
     * The overlay files to ask, by clean name, with the mod each belongs to, and the mark that this
     * loader draws overlays at all, which a test asks before it opens a screen.
     */
    static void init(Map<String, Long> files, Map<String, String> mods) {
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

        // The first frame is where the screen-open hook's clearing has to show: a joined client
        // draws frames between a server's open and its read, so the mark alone cannot tell a hook
        // that ran before this frame from one that ran after it (#1391).
        GrugOverlay.noteFrame(container);

        GuiGraphics graphics = event.getGuiGraphics();
        String testMod = GrugTestState.currentTestMod;
        // A test that parks the cursor for a capture tells the overlay where the mouse is, rather
        // than relying on the game's own pointer having followed the warp: this version reads its
        // pointer from the window's callbacks, and a warp raises none of them. The parked point is
        // in window pixels, which the screen's scaled size turns into the pixels an overlay gets.
        Window window = Minecraft.getInstance().getWindow();
        double[] parked =
                GrugRunWindow.parkedMousePosition(
                        window.getWidth(), window.getHeight(), container.width, container.height);
        double mouseX = parked == null ? event.getMouseX() : parked[0];
        double mouseY = parked == null ? event.getMouseY() : parked[1];
        for (GrugOverlayScripts.Script overlay : overlays()) {
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
                            mouseX,
                            mouseY);

            // A test waits for its own mod's overlay, not for another mod's on the same screen: the
            // coverage mod's overlay draws on every screen too, and would otherwise hide a port
            // whose render export never ran or declined to draw. A render with nothing to draw is
            // not a frame of the test's list.
            if (!geometry.items().isEmpty()
                    || !geometry.boxes().isEmpty()
                    || !geometry.texts().isEmpty()
                    || !geometry.tooltips().isEmpty()) {
                if (overlay.mod.equals(testMod)) {
                    GrugOverlay.noteDrawn();
                }
            }

            for (GrugScreenItemDraw item : geometry.items()) {
                graphics.renderItem(
                        new ItemStack((Item) item.item()), (int) item.x(), (int) item.y());
            }
            for (GrugScreenText text : geometry.texts()) {
                graphics.drawString(
                        Minecraft.getInstance().font,
                        text.text(),
                        (int) text.x(),
                        (int) text.y(),
                        0xFF000000 | text.rgb());
            }
            for (GrugScreenBox box : geometry.boxes()) {
                graphics.fill(
                        (int) box.x1(), (int) box.y1(), (int) box.x2(), (int) box.y2(), HIGHLIGHT);
            }
            // The tooltip goes last, so it lies over the screen and over the overlay's own drawing.
            // This version's own tooltip is the look: its default positioner flips one that would
            // run off the window, as vanilla's does.
            for (GrugScreenTooltip tooltip : geometry.tooltips()) {
                graphics.renderTooltip(
                        Minecraft.getInstance().font,
                        Component.literal(tooltip.text()),
                        (int) tooltip.x(),
                        (int) tooltip.y());
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
        heldKey = -1;
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

        for (GrugOverlayScripts.Script overlay : overlays()) {
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
                            event.getButton(),
                            Screen.hasShiftDown());
            if (taken) {
                GrugOverlay.noteTestPressTaken();
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * Lets an overlay take a mouse wheel movement before the screen's own handler sees it.
     *
     * <p>The movement is cancelled when an overlay answers that it took it, and asking stops at the
     * first overlay that does. The delta is the event's vertical one, in notches, positive up.
     */
    @SubscribeEvent
    public static void onScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        AbstractContainerScreen<?> container = eligible(event.getScreen());
        if (container == null) return;

        for (GrugOverlayScripts.Script overlay : overlays()) {
            boolean taken =
                    GrugOverlay.scroll(
                            overlay.entityHandle,
                            overlay.scrollFnId,
                            container.width,
                            container.height,
                            container.getGuiLeft(),
                            container.getGuiTop(),
                            container.getXSize(),
                            container.getYSize(),
                            event.getMouseX(),
                            event.getMouseY(),
                            event.getDeltaY());
            if (taken) {
                GrugOverlay.noteTestPressTaken();
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * Lets an overlay take a key press before the screen's own handler sees it.
     *
     * <p>Only a letter or a digit key, the space bar and backspace are asked, given as the numbers
     * {@code OverlayKeyContext.key} documents, which are the same on every loader; the key is
     * cancelled when an overlay took it, and asking stops at the first overlay that does. A press
     * the game reports again while its key is still held is delivered too, with {@code
     * OverlayKeyContext.repeat} true, so a toggle flips once per press and a text field types its
     * character again.
     */
    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        AbstractContainerScreen<?> container = eligible(event.getScreen());
        if (container == null) return;
        int key = overlayKey(event.getKeyCode());
        if (key < 0) return;

        boolean repeat = event.getKeyCode() == heldKey;
        heldKey = event.getKeyCode();

        boolean ctrl = (event.getModifiers() & controlModifier()) != 0;
        boolean shift = (event.getModifiers() & shiftModifier()) != 0;
        if (deliverKey(event.getScreen(), 0, key, ctrl, shift, repeat)) {
            event.setCanceled(true);
        }
    }

    /**
     * Lets an overlay take a typed character before the screen's own handler sees it, which is the
     * event Forge fires beside the key press (#1741). The character is the one the game produced,
     * so shift and the keyboard layout are already applied, and a punctuation key arrives here with
     * no key number of its own.
     *
     * <p>An AltGr press carries the control and alt bits together on Windows and its character is
     * the layout's own, so {@link GrugOverlay#characterEventIsChord} decides whether the event is a
     * chord: that answer is the control flag the pass is given, so a German at sign or a French
     * tilde is typed while a control chord is not (#1741).
     *
     * <p>The event carries no repeat bit, so the character of a held key's repeat is delivered with
     * {@code OverlayKeyContext.repeat} false while the key press beside it carries the answer. A
     * held printable key still types its character again for every repeat.
     */
    @SubscribeEvent
    public static void onScreenCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        AbstractContainerScreen<?> container = eligible(event.getScreen());
        if (container == null) return;
        int character = GrugOverlay.characterCode(event.getCodePoint());
        if (character == 0) return;
        boolean ctrl = (event.getModifiers() & controlModifier()) != 0;
        boolean alt = (event.getModifiers() & altModifier()) != 0;
        boolean chord = GrugOverlay.characterEventIsChord(ctrl, alt);
        boolean shift = (event.getModifiers() & shiftModifier()) != 0;
        if (deliverKey(event.getScreen(), character, 0, chord, shift, false)) {
            event.setCanceled(true);
        }
    }

    /**
     * Asks every overlay about one key press or typed character and answers whether one took it,
     * recording the answer for {@code TestTickContext.press_screen_key}. Shift is the event's own
     * flag, which a text field extends its selection with.
     */
    private static boolean deliverKey(
            Screen screen,
            double character,
            double key,
            boolean ctrl,
            boolean shift,
            boolean repeat) {
        for (GrugOverlayScripts.Script overlay : overlays()) {
            if (GrugOverlay.key(
                    overlay.entityHandle, overlay.keyFnId, key, character, ctrl, shift, repeat)) {
                GrugOverlay.noteTestPressTaken();
                return true;
            }
        }
        return false;
    }

    /**
     * The number an overlay's key() is given for a GLFW key code, or -1 for a key that is not
     * asked: the upper case character code of a letter or a digit key, the ASCII codes of the space
     * bar and of backspace, and GLFW's own codes of the keys with no character of their own, which
     * are the API's numbers from 256 up, so every loader gives the same number.
     */
    static int overlayKey(int glfwKey) {
        if (glfwKey == GLFW_KEY_BACKSPACE) return GrugOverlay.KEY_BACKSPACE;
        if (glfwKey == GLFW_KEY_SPACE) return GrugOverlay.KEY_SPACE;
        // GLFW's own codes for the keys with no character are the API's, so they pass through.
        if (GrugOverlay.isNamedKey(glfwKey)) return glfwKey;
        boolean letter = glfwKey >= 'A' && glfwKey <= 'Z';
        boolean digit = glfwKey >= '0' && glfwKey <= '9';
        return letter || digit ? glfwKey : -1;
    }

    /**
     * The GLFW key code the number a test presses stands for, or -1 when the number is not a key
     * the test presses: a letter, a digit, the space bar and backspace are pressed as their key,
     * and a number the API names from 256 up is one of GLFW's own codes and is pressed as it is
     * (256 is Escape, which the screen acts on). A printable number that names no key is not a key
     * at all, and {@code press_screen_key} types it instead; a number that names neither is a press
     * that presses nothing, which {@code press_screen_key} reports as a host function error. That
     * last arm includes 258 and 259, Tab and GLFW's own backspace code, the two GLFW codes between
     * the API's named keys that no overlay is given: the API's backspace is 8 (#1748).
     */
    static int glfwKey(int overlayKey) {
        if (overlayKey == GrugOverlay.KEY_BACKSPACE) return GLFW_KEY_BACKSPACE;
        if (overlayKey == GrugOverlay.KEY_SPACE) return GLFW_KEY_SPACE;
        // GLFW's own codes for the keys with no character are the API's, so the named ones pass
        // through and nothing else above the printable range does.
        if (GrugOverlay.isNamedKey(overlayKey)) return overlayKey;
        boolean letter = overlayKey >= 'A' && overlayKey <= 'Z';
        boolean digit = overlayKey >= '0' && overlayKey <= '9';
        return letter || digit ? overlayKey : -1;
    }

    /**
     * Whether a test's press of an overlay key number presses nothing at all: no key of its own and
     * no typed character either, since control held types none. A loader before 1.20.6 has no key
     * for such a number and reports the press as a host function error, so this loader answers the
     * same way rather than pressing nothing silently (#1741).
     */
    static boolean pressesNothing(int overlayKey, boolean ctrl) {
        return glfwKey(overlayKey) < 0
                && (ctrl ? 0 : GrugOverlay.typedCharacterFor(overlayKey)) == 0;
    }

    /** The key held since its first press, or -1: a second press event for it is a key repeat. */
    private static int heldKey = -1;

    /** Lets go of the held key when the screen reports its release. */
    @SubscribeEvent
    public static void onScreenKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (event.getKeyCode() == heldKey) heldKey = -1;
    }

    /**
     * The modifier bit that counts as control for the hotkey: Command on macOS and Control
     * elsewhere, which is what {@code Screen.hasControlDown} (and JEI's CONTROL_OR_COMMAND) tests.
     */
    static int controlModifier() {
        return Minecraft.ON_OSX ? GLFW_MOD_SUPER : GLFW_MOD_CONTROL;
    }

    /**
     * The GLFW modifier bit for alt, which an AltGr press carries beside control on Windows:
     * together they say the event is that keyboard layout's character and not a control chord
     * (#1741).
     */
    static int altModifier() {
        return GLFW_MOD_ALT;
    }

    /**
     * The GLFW modifier bit for shift, which a text field extends its selection with and which a
     * control chord reads beside control.
     */
    static int shiftModifier() {
        return GLFW_MOD_SHIFT;
    }

    private static final int GLFW_MOD_SHIFT = 1;
    private static final int GLFW_MOD_CONTROL = 2;
    private static final int GLFW_MOD_ALT = 4;
    private static final int GLFW_MOD_SUPER = 8;

    /**
     * The container screen an overlay may draw on, or null when this screen is not one or has no
     * size yet, which is what JEI's own screen check answers.
     *
     * <p>A grug mod's own {@link GrugScreen} is left out on purpose: the first version draws on the
     * game's other container screens, and drawing on a mod's own panel would change the GUI goldens
     * of the mods that have one. JEI itself draws there, so about.json records the difference.
     */
    private static AbstractContainerScreen<?> eligible(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) return null;
        if (container instanceof GrugScreen) return null;
        if (container.width <= 0 || container.height <= 0) return null;
        return container;
    }

    private static final GrugWorldKeys worldKeys = new GrugWorldKeys();

    private static final int GLFW_KEY_SPACE = 32;
    private static final int GLFW_KEY_BACKSPACE = 259;
    private static final int GLFW_KEY_LEFT_CONTROL = 341;
    private static final int GLFW_KEY_RIGHT_CONTROL = 345;
    private static final int GLFW_KEY_LEFT_SUPER = 343;
    private static final int GLFW_KEY_RIGHT_SUPER = 347;

    /**
     * Draws every overlay's HUD export on top of the finished HUD, in the GUI's scaled pixels,
     * while no screen is open and F1 has not hidden the HUD. Called from the end of {@code
     * Gui.render}.
     */
    public static void drawHud(Minecraft minecraft, GuiGraphics graphics) {
        if (minecraft.level == null || minecraft.screen != null || minecraft.options.hideGui) {
            return;
        }
        String testMod = GrugTestState.currentTestMod;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            List<GrugWorldOverlay.Text> texts =
                    GrugWorldOverlay.hud(
                            overlay.entityHandle,
                            overlay.hudFnId,
                            minecraft.getWindow().getGuiScaledWidth(),
                            minecraft.getWindow().getGuiScaledHeight());
            for (GrugWorldOverlay.Text text : texts) {
                graphics.drawString(
                        minecraft.font,
                        text.text(),
                        (int) text.x(),
                        (int) text.y(),
                        0xFF000000 | text.rgb());
            }
            if (!texts.isEmpty() && overlay.mod.equals(testMod)) {
                GrugWorldOverlay.noteDrawn();
            }
        }
        graphics.flush();
    }

    /**
     * Draws every overlay's world export as lines and translucent triangles after the particles,
     * depth-tested against the level. The level's model-view matrix is already on the render system
     * when this stage fires, so both go in through an identity pose, camera-relative.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Camera camera = event.getCamera();
        Vec3 position = camera.getPosition();
        String testMod = GrugTestState.currentTestMod;
        for (GrugOverlayScripts.Script overlay : overlays()) {
            List<GrugWorldOverlay.Line> lines =
                    GrugWorldOverlay.world(
                            overlay.entityHandle,
                            overlay.worldFnId,
                            event.getPartialTick(),
                            position.x,
                            position.y,
                            position.z);
            List<GrugWorldOverlay.Triangle> triangles = GrugWorldOverlay.lastTriangles();
            if (lines.isEmpty() && triangles.isEmpty()) continue;
            if (overlay.mod.equals(testMod)) {
                GrugWorldOverlay.noteDrawn();
            }
            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            PoseStack.Pose pose = new PoseStack().last();
            if (!lines.isEmpty()) {
                VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
                for (GrugWorldOverlay.Line line : lines) {
                    int rgb = line.rgb();
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    float x1 = (float) (line.x1() - position.x);
                    float y1 = (float) (line.y1() - position.y);
                    float z1 = (float) (line.z1() - position.z);
                    float x2 = (float) (line.x2() - position.x);
                    float y2 = (float) (line.y2() - position.y);
                    float z2 = (float) (line.z2() - position.z);
                    float dx = x2 - x1;
                    float dy = y2 - y1;
                    float dz = z2 - z1;
                    float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (length > 0.0F) {
                        dx /= length;
                        dy /= length;
                        dz /= length;
                    }
                    consumer.vertex(pose.pose(), x1, y1, z1)
                            .color(r, g, b, 255)
                            .normal(pose, dx, dy, dz)
                            .endVertex();
                    consumer.vertex(pose.pose(), x2, y2, z2)
                            .color(r, g, b, 255)
                            .normal(pose, dx, dy, dz)
                            .endVertex();
                }
                buffers.endBatch(RenderType.lines());
            }
            drawTriangles(pose, triangles, position);
        }
    }

    /**
     * Draws the world pass's translucent triangles after its lines with the position-color shader,
     * blending on, culling off and depth writes off, so terrain in front still hides a triangle
     * while two translucent shells do not hide each other and the inside of a closed shape is
     * drawn. The version's debug quad render type writes depth, which would hide the far shell, so
     * the state is set by hand and restored after the draw.
     */
    private static void drawTriangles(
            PoseStack.Pose pose, List<GrugWorldOverlay.Triangle> triangles, Vec3 position) {
        if (triangles.isEmpty()) return;
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (GrugWorldOverlay.Triangle triangle : triangles) {
            int argb = triangle.argb();
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            int a = (argb >>> 24) & 0xFF;
            for (int corner = 0; corner < 3; corner++) {
                builder.vertex(
                                pose.pose(),
                                (float) (triangle.corner(corner, 0) - position.x),
                                (float) (triangle.corner(corner, 1) - position.y),
                                (float) (triangle.corner(corner, 2) - position.z))
                        .color(r, g, b, a)
                        .endVertex();
            }
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * Polls the letter and digit keys once per client tick and asks the overlays' world_key export
     * about each fresh press made with no screen open. A key held across ticks, or through a
     * screen, fires once at most.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        // The state is recorded on every tick, with no level loaded too, so a key still held when a
        // level loads is not taken for a fresh press; only the delivery waits for a level.
        boolean open = minecraft.screen == null && minecraft.level != null;
        long window = minecraft.getWindow().getWindow();
        for (int code = '0'; code <= 'Z'; code++) {
            if (!GrugWorldKeys.tracked(code)) continue;
            // GLFW's letter and digit key codes are the upper case ASCII codes.
            boolean down = InputConstants.isKeyDown(window, code);
            if (worldKeys.deliver(code, down, open)) {
                deliverWorldKey(code, controlDown(window));
            }
        }
    }

    private static boolean controlDown(long window) {
        return InputConstants.isKeyDown(window, GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_CONTROL)
                || (Minecraft.ON_OSX
                        && (InputConstants.isKeyDown(window, GLFW_KEY_LEFT_SUPER)
                                || InputConstants.isKeyDown(window, GLFW_KEY_RIGHT_SUPER)));
    }

    private static void deliverWorldKey(int code, boolean ctrl) {
        for (GrugOverlayScripts.Script overlay : overlays()) {
            GrugWorldOverlay.key(overlay.entityHandle, overlay.worldKeyFnId, code, ctrl);
        }
    }

    /** Test only: delivers one press of the key whose character code is {@code code}. */
    static void pressWorldKey(int code, boolean ctrl) {
        GrugWorldOverlay.clearDrawn();
        deliverWorldKey(code, ctrl);
    }
}
