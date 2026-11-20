package net.grug.minecraft.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

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
import net.grug.minecraft.grug.GrugWorldKeys;
import net.grug.minecraft.grug.GrugWorldOverlay;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
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
        final long keyFnId;
        final long scrollFnId;
        final long hudFnId;
        final long worldFnId;
        final long worldKeyFnId;
        final String mod;

        Overlay(
                long entityHandle,
                List<GrugObject> owner,
                long renderFnId,
                long clickFnId,
                long keyFnId,
                long scrollFnId,
                long hudFnId,
                long worldFnId,
                long worldKeyFnId,
                String mod) {
            this.entityHandle = entityHandle;
            this.owner = owner;
            this.renderFnId = renderFnId;
            this.clickFnId = clickFnId;
            this.keyFnId = keyFnId;
            this.scrollFnId = scrollFnId;
            this.hudFnId = hudFnId;
            this.worldFnId = worldFnId;
            this.worldKeyFnId = worldKeyFnId;
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
        GrugWorldOverlay.noteSupported();
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
                            Grug.getExportFnId("Overlay", "render_hud"),
                            Grug.getExportFnId("Overlay", "render_world"),
                            Grug.getExportFnId("Overlay", "world_key"),
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

        // The first frame is where the screen-open hook's clearing has to show: a joined client
        // draws frames between a server's open and its read, so the mark alone cannot tell a hook
        // that ran before this frame from one that ran after it (#1391).
        GrugOverlay.noteFrame(container);

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

        ensureCreated();

        for (Overlay overlay : overlays) {
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
     * <p>Only a letter or a digit key is asked, given as its upper case character code, which is
     * the same number on every loader; the key is cancelled when an overlay took it, and asking
     * stops at the first overlay that does.
     */
    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        AbstractContainerScreen<?> container = eligible(event.getScreen());
        if (container == null) return;
        int key = event.getKeyCode();
        boolean letter = key >= 'A' && key <= 'Z';
        boolean digit = key >= '0' && key <= '9';
        if (!letter && !digit) return;

        ensureCreated();

        // A held key repeats the press event, and a toggle must flip once per press.
        if (key == heldKey) return;
        heldKey = key;

        boolean ctrl = (event.getModifiers() & controlModifier()) != 0;
        for (Overlay overlay : overlays) {
            if (GrugOverlay.key(overlay.entityHandle, overlay.keyFnId, key, ctrl)) {
                GrugOverlay.noteTestPressTaken();
                event.setCanceled(true);
                return;
            }
        }
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

    private static final int GLFW_MOD_CONTROL = 2;
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
        ensureCreated();
        String testMod = GrugTestState.currentTestMod;
        for (Overlay overlay : overlays) {
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
     * Draws every overlay's world export as lines after the particles, depth-tested against the
     * level. The level's model-view matrix is already on the render system when this stage fires,
     * so the lines go in through an identity pose, camera-relative.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        ensureCreated();
        Camera camera = event.getCamera();
        Vec3 position = camera.getPosition();
        String testMod = GrugTestState.currentTestMod;
        for (Overlay overlay : overlays) {
            List<GrugWorldOverlay.Line> lines =
                    GrugWorldOverlay.world(
                            overlay.entityHandle,
                            overlay.worldFnId,
                            event.getPartialTick(),
                            position.x,
                            position.y,
                            position.z);
            if (lines.isEmpty()) continue;
            if (overlay.mod.equals(testMod)) {
                GrugWorldOverlay.noteDrawn();
            }
            MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
            VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
            PoseStack.Pose pose = new PoseStack().last();
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
        ensureCreated();
        for (Overlay overlay : overlays) {
            GrugWorldOverlay.key(overlay.entityHandle, overlay.worldKeyFnId, code, ctrl);
        }
    }

    /** Test only: delivers one press of the key whose character code is {@code code}. */
    static void pressWorldKey(int code, boolean ctrl) {
        GrugWorldOverlay.clearDrawn();
        deliverWorldKey(code, ctrl);
    }
}
