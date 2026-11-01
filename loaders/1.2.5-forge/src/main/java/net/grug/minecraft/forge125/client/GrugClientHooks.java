package net.grug.minecraft.forge125.client;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugFrameRequest;
import net.grug.minecraft.core.GrugRunWindow;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.forge125.mod_Grug;
import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugIdShortage;
import net.grug.minecraft.grug.GrugModManager;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.grug.GrugModsScreenModel;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.grug.GrugScreenshots;
import net.minecraft.client.Minecraft;
import net.minecraft.src.GrugTitleButton;
import net.minecraft.src.GrugTorchFlicker;
import net.minecraft.src.GuiIngameMenu;
import net.minecraft.src.GuiMainMenu;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.KeyBinding;
import net.minecraft.src.ModLoader;
import net.minecraft.src.PlayerControllerSP;
import net.minecraft.src.TileEntity;
import net.minecraft.src.WorldSettings;
import net.minecraft.src.WorldType;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Window;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/**
 * The 1.2.5 client's per-tick work, split by the cadence each part needs.
 *
 * <p>1.2.5 has no Mixin, so this is an FML tick handler rather than an injected method. FML's
 * ModLoader compatibility hooks can subscribe to render ticks or to game ticks but never both, and
 * the game tick is the one the other four loaders drive the test runner from, while the tile entity
 * workaround in {@link #renderTick} has to run before a frame renders. Subscribing to both keeps
 * the runner on a game tick here too; see #135.
 *
 * <p>It exists as its own class so the runner logic stays out of the entry point, matching how the
 * other loaders keep their tick logic in {@code net.grug.*}. Only the 1.2.5-specific glue is
 * measured-out here: the window, GL and cursor bookkeeping a run takes over now drives {@link
 * GrugRunWindow} in core, which core's tests measure directly.
 */
public class GrugClientHooks implements ITickHandler {

    /**
     * GAME is the client tick, the cadence the other loaders drive the runner from, and RENDER
     * fires once per frame for the work that has to happen before a frame is drawn.
     */
    private static final EnumSet<TickType> TICKS = EnumSet.of(TickType.GAME, TickType.RENDER);

    private boolean titleLogged = false;
    private boolean idScreenShown = false;
    private boolean worldRequested = false;
    private boolean ciTestsRan = false;

    /**
     * How long to wait for the client to receive the world before failing the run. A placement into
     * a chunk that has not arrived is silently replaced later, so the wait is what keeps a setup
     * from building into a placeholder chunk; the deadline is what keeps a world that never loads
     * from hanging.
     */
    private static final long WORLD_READY_TIMEOUT_MILLIS = 60000;

    private long ciWorldWaitStartMillis = 0;

    private GrugTestRunner testRunner = null;
    private boolean testRunnerFromCI = false;

    /**
     * R starts a test run and M forces the screenshot resolution by hand, matching the other four
     * loaders. 1.2.5 registers these by constructing KeyBindings, which the game's own input loop
     * feeds: a key press sits in the binding until {@code isPressed()} reads it, so a press is not
     * lost across a tick that is skipped, and the tick that does read it is the one that acts.
     */
    private static final KeyBinding RUN_TESTS_KEY =
            new KeyBinding("key.grug.run_tests", Keyboard.KEY_R);

    private static final KeyBinding FORCE_RESOLUTION_KEY =
            new KeyBinding("key.grug.force_test_resolution", Keyboard.KEY_M);

    /**
     * The 1.2.5 half of the shared run state machine: LWJGL's Display, the AWT canvas it is
     * parented to, GL dither and the mouse. The save/force/restore transitions themselves live in
     * {@link GrugRunWindow}, which is where they are measured.
     */
    private static final GrugRunWindow.Display DISPLAY =
            new GrugRunWindow.Display() {
                @Override
                public int width() {
                    return Display.getWidth();
                }

                @Override
                public int height() {
                    return Display.getHeight();
                }

                @Override
                public void setSize(int width, int height) {
                    applyDisplayMode(new DisplayMode(width, height));
                }

                @Override
                public boolean ditherOn() {
                    return GL11.glIsEnabled(GL11.GL_DITHER);
                }

                @Override
                public void setDither(boolean on) {
                    if (on) {
                        GL11.glEnable(GL11.GL_DITHER);
                    } else {
                        GL11.glDisable(GL11.GL_DITHER);
                    }
                }

                @Override
                public double[] cursorPos() {
                    return new double[] {Mouse.getX(), Mouse.getY()};
                }

                @Override
                public void setCursor(double x, double y) {
                    Mouse.setCursorPosition((int) x, (int) y);
                }
            };

    /** The two keybindings, consumed as the shared state machine polls them. */
    private static final GrugRunWindow.Keys KEYS =
            new GrugRunWindow.Keys() {
                @Override
                public boolean runPressed() {
                    return RUN_TESTS_KEY.isPressed();
                }

                @Override
                public boolean forceResolutionPressed() {
                    return FORCE_RESOLUTION_KEY.isPressed();
                }
            };

    /**
     * The shared state machine for the run's window, dither and cursor, driven by the glue above.
     * An instance (not a static) so a test or a second handler could ever hold its own copy.
     */
    private final GrugRunWindow window = new GrugRunWindow(DISPLAY);

    /** The smallest render distance this version allows, out of far, normal, short and tiny. */
    private static final int TINY_RENDER_DISTANCE = 3;

    /**
     * The property the runtime's {@code setupFog} reads to push the distance fog out of the world.
     * It is a property rather than a direct call because the game classes are loaded by
     * launchwrapper's parent loader and cannot see this mod's classes; the runtime jar is patched
     * with the read at build time. See {@code tools/DisableFog.java} and #444.
     */
    private static final String FOG_PROPERTY = "grug.disable.fog";

    /** The render distance a run found, put back when the run ends. -1 while no run owns one. */
    private static int savedRenderDistance = -1;

    /** The particle setting a run found, put back with the render distance. */
    private static int savedParticleSetting = -1;

    /** The game's "Minimal" particle setting, which spawns no ordinary particle at all. */
    private static final int MINIMAL_PARTICLES = 2;

    /**
     * Shrinks the view to the smallest the game allows before a run starts, remembering the old
     * distance. A run's room is at most 17 blocks across, so a default view only streams in far
     * chunks whose light and mesh rebuilds the runner then has to work through, which both slows
     * the run down and, on Beta, makes the frame cost grow across repeats as the world streams in
     * (#422). The run puts the old distance back when it ends. See #444.
     *
     * <p>Particles go to minimal for the run too. They come from the world's random, so a capture
     * with one in it is a different picture on every run, and a reference mod's particles, such as
     * the redstone engine's (#581), would fail its screenshots at random.
     */
    @GrugGenerated("dev-only: the view is shrunk for a run")
    private static void shrinkViewForRun(Minecraft minecraft) {
        if (savedRenderDistance >= 0) {
            return;
        }
        savedRenderDistance = minecraft.gameSettings.renderDistance;
        minecraft.gameSettings.renderDistance = TINY_RENDER_DISTANCE;
        savedParticleSetting = minecraft.gameSettings.particleSetting;
        minecraft.gameSettings.particleSetting = MINIMAL_PARTICLES;
        System.setProperty(FOG_PROPERTY, "1");
        if (minecraft.renderGlobal != null) {
            minecraft.renderGlobal.loadRenderers();
        }
    }

    /** Puts the render distance back, and only if a run changed it. */
    @GrugGenerated("dev-only: the view is shrunk for a run")
    private static void restoreViewAfterRun(Minecraft minecraft) {
        if (savedRenderDistance < 0) {
            return;
        }
        minecraft.gameSettings.renderDistance = savedRenderDistance;
        savedRenderDistance = -1;
        minecraft.gameSettings.particleSetting = savedParticleSetting;
        savedParticleSetting = -1;
        System.clearProperty(FOG_PROPERTY);
        if (minecraft.renderGlobal != null) {
            minecraft.renderGlobal.loadRenderers();
        }
    }

    /**
     * The property the runtime's {@code updateCameraAndRender} reads to leave a frame undrawn. It
     * is a property rather than a direct call because the game classes are loaded by
     * launchwrapper's parent loader and cannot see this mod's classes; the runtime jar is patched
     * with the read at build time. See {@code tools/SkipFrame.java} and #770.
     */
    private static final String SKIP_FRAME_PROPERTY = "grug.skip.frame";

    /**
     * Whether the frame the last render tick started was drawn: 1 for drawn, 0 for skipped, and -1
     * before the first frame. The property above carries the same answer to the runtime, and is
     * only written when it changes because this runs once per frame.
     */
    private int frameDrawn = -1;

    @Override
    public void tickStart(EnumSet<TickType> types, Object... data) {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        if (types.contains(TickType.GAME)) {
            gameTick(minecraft);
        }
        if (types.contains(TickType.RENDER)) {
            publishFrameRequest();
            renderTick(minecraft);
        }
    }

    @Override
    public void tickEnd(EnumSet<TickType> types, Object... data) {
        // Everything else runs at tick start, like the injected client tick hooks on the other
        // loaders. The frame hook is the exception: the frame has just been drawn, so this is where
        // the settle answer samples it. See ModLoaderAdapter#onFrameRendered.
        //
        // Only a frame that was drawn is sampled: 1.2.5 has no Mixin to cancel the draw with, so
        // its frame hook is this tick and it fires whether or not the frame was drawn. A skipped
        // frame left the previous one where a capture would read it, so counting it would let a
        // capture through on frames nobody drew. See GrugFrameRequest and #770.
        if (types.contains(TickType.RENDER) && frameDrawn == 1) {
            // The screen has drawn and the frame has not been presented yet, which is where an
            // overlay's icons and highlight go. See GrugOverlayHost.
            GrugOverlayHost.renderCurrent(ModLoader.getMinecraftInstance());
            GrugCore.getAdapter().onFrameRendered();
        }
    }

    /**
     * Tells the patched runtime whether this frame is to be drawn, from the running test's frame
     * request.
     *
     * <p>A test that never captures asks for no frame, so the frame is left undrawn: the runtime's
     * guard sits after the lightmap update and the mouse work and before the level, its chunk
     * rebuilds and the GUI. Drawing is on outside a test, so the title screen, the world load and
     * the frames between two tests draw as they always did. Written only when it changes, since
     * this runs once per frame. See {@code SkipFrame} and #770.
     */
    private void publishFrameRequest() {
        int draw = GrugFrameRequest.wanted() ? 1 : 0;
        if (draw == frameDrawn) {
            return;
        }
        frameDrawn = draw;
        if (draw == 1) {
            System.clearProperty(SKIP_FRAME_PROPERTY);
        } else {
            System.setProperty(SKIP_FRAME_PROPERTY, "1");
        }
    }

    @Override
    public EnumSet<TickType> ticks() {
        return TICKS;
    }

    @Override
    public String getLabel() {
        return "grug";
    }

    /**
     * The game tick, which is where the test runner is driven: one {@code Test.tick()} call per
     * game tick, the cadence the other four loaders use.
     */
    private void gameTick(Minecraft minecraft) {
        // The grug sprites cannot be handed to the texture manager before the atlas exists: FML
        // asks
        // the client handler how large a tile is, and that answer is the size setupTexture recorded
        // when the pack's /terrain.png was first loaded and stitched, which is a world being drawn.
        // The first tick that has a render engine is the earliest point there is anything to
        // register into.
        if (minecraft.renderEngine != null) {
            GrugTextures.register(minecraft.renderEngine);
            GrugItemRenderer.register();
        }

        if ("true".equals(System.getenv("GRUG_CI")) && !ciTestsRan && minecraft.thePlayer != null) {
            // The runner starts as soon as the player exists, which can be before the client has
            // received the chunks the tests build in. Wait for them; a world that never arrives
            // fails the run instead of starting tests against placeholder chunks.
            if (ciWorldWaitStartMillis == 0) {
                ciWorldWaitStartMillis = System.currentTimeMillis();
            }
            boolean ready = GrugCore.getAdapter().isWorldReady(minecraft.thePlayer);
            if (ready) {
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - ciWorldWaitStartMillis)
                                + " ms. Firing tests!");
                ciTestsRan = true;
                if (window.beginRun()) {
                    shrinkViewForRun(minecraft);
                    testRunner = new GrugTestRunner();
                    testRunnerFromCI = true;
                }
            } else if (System.currentTimeMillis() - ciWorldWaitStartMillis
                    >= WORLD_READY_TIMEOUT_MILLIS) {
                System.out.println("[GRUG CI] FAIL world readiness");
                System.out.println(
                        "[GRUG CI] The client did not receive the world within "
                                + (WORLD_READY_TIMEOUT_MILLIS / 1000)
                                + " seconds, so the tests were not started.");
                ciTestsRan = true;
            }
        }

        // One Test.tick() call per game tick, so ticks separate a test's invocations.
        if (testRunner != null) {
            testRunner.tick(minecraft.thePlayer);
            if (testRunner.isFinished()) {
                boolean fromCI = testRunnerFromCI;
                testRunner = null;
                testRunnerFromCI = false;
                window.endRun();
                restoreViewAfterRun(minecraft);
                if (fromCI) {
                    minecraft.shutdownMinecraftApplet();
                }
            }
        }

        // Only once a world is loaded: the runner needs a player to tick, and the resolution toggle
        // resizes a window that has nothing to screenshot yet. The polls consume the presses they
        // report, so this has to run every tick a press is meant to count.
        if (minecraft.thePlayer != null) {
            if (window.tick(KEYS)) {
                shrinkViewForRun(minecraft);
                testRunner = new GrugTestRunner();
                testRunnerFromCI = false;
            }
        }

        updateCursorReadout(minecraft);

        String[] updatedResources = GrugModsWatcher.update(GrugClientHooks::logError);
        if (updatedResources.length > 0 && minecraft.renderEngine != null) {
            String[] reloaded = GrugModsWatcher.takePendingReloadPaths();
            minecraft.renderEngine.refreshTextures();
            // refreshTextures re-stitches every atlas from the texture pack, and in doing so it
            // runs
            // setup() on every registered effect, which allocates that effect's pixels again, and
            // it
            // overwrites every tile. The grug sprites have to read their files after that rather
            // than
            // before, because the change the engine reported is a file in the mods tree that no
            // atlas
            // ever held. Each read pushes its own pixels into its tile, so a reload does not wait
            // on
            // the game's own per-tick upload.
            GrugTextures.reload();
            // The textures draw_model faces bind themselves, outside the atlases.
            GrugModelRenderer.reload(minecraft.renderEngine);
            // The calls above finish their work before they return, so the reload is done here
            // and the tests' reload_count can rise.
            GrugModsWatcher.noteLoaderReloadFinished(reloaded);
        }

        if (minecraft.thePlayer != null) {
            synchronized (GrugErrors.runtimeErrorQueue) {
                while (!GrugErrors.runtimeErrorQueue.isEmpty()) {
                    logError(GrugErrors.runtimeErrorQueue.poll());
                }
            }
            synchronized (GrugErrors.printQueue) {
                while (!GrugErrors.printQueue.isEmpty()) {
                    sendMessage(GrugErrors.printQueue.poll(), "");
                }
            }
        }
    }

    /** The frame, for the work that has to happen before one is drawn. */
    private void renderTick(Minecraft minecraft) {
        // The lightmap multiplies a random walk into the block-light color (see GrugTorchFlicker),
        // so pin it on every frame a run draws: the value the game picked this tick would otherwise
        // make the same scene a light step brighter or darker than another run's capture of it.
        if (testRunner != null) {
            GrugTorchFlicker.pin(minecraft.entityRenderer);
            GrugTorchFlicker.pinVignette(minecraft.ingameGUI);
        }

        // The main menu has no hook to add a button through, and rebuilds its control list when the
        // window is resized, so the button is put back whenever the list lacks it (#335).
        if (minecraft.currentScreen instanceof GuiMainMenu
                && !GrugTitleButton.has(minecraft.currentScreen, GrugModsButton.class)) {
            GrugTitleButton.add(
                    minecraft.currentScreen, new GrugModsButton(minecraft.currentScreen));
        }

        // A game short of block or item ids registered none of that kind and let the title screen
        // come up, so the player can turn mods off here (#510). Nothing but the mod manager is
        // safe to enter: a world saved without the blocks would lose them.
        if (minecraft.currentScreen instanceof GuiMainMenu && !idScreenShown) {
            idScreenShown = true;
            if (!GrugIdShortage.all().isEmpty()) {
                // No one is there to answer the screen of a CI run, which should say why it
                // stopped.
                if ("true".equals(System.getenv("GRUG_CI"))) GrugIdShortage.failIfShort();
                minecraft.displayGuiScreen(
                        new GrugModsScreen(
                                minecraft.currentScreen,
                                new GrugModsScreenModel(
                                        GrugModManager.of(minecraft.mcDataDir), 5)));
            }
        }

        // The title screen is a GUI and has no world yet, so the game tick never fires there; this
        // is what notices the title screen and kicks off the CI world load.
        if ("true".equals(System.getenv("GRUG_CI"))
                && !worldRequested
                && minecraft.currentScreen instanceof GuiMainMenu) {
            if (!titleLogged) {
                System.out.println("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");
                titleLogged = true;
            }
            worldRequested = true;
            // GuiSelectWorld creates the controller before loading; startWorld assumes it exists.
            minecraft.playerController = new PlayerControllerSP(minecraft);
            minecraft.startWorld(
                    "World1", "World1", new WorldSettings(0L, 0, true, false, WorldType.DEFAULT));
            minecraft.displayGuiScreen((GuiScreen) null);
        }

        // The game opens its in-game menu half a second after the window goes inactive, which on
        // the headless display the tests run on it always is, and the menu pauses the world behind
        // it. While a run is going, swap it for the empty screen the screenshot tests close to, so
        // a test that just closed a screen does not have the menu appear over the world it built.
        // See GrugEmptyScreen.
        if (testRunner != null && minecraft.currentScreen instanceof GuiIngameMenu) {
            minecraft.displayGuiScreen(new GrugEmptyScreen());
        }

        // A captured frame must not depend on where the invisible pointer happens to be: 1.2.5's
        // GuiContainer paints a 50% white highlight over the slot under the mouse, and a tooltip
        // follows it, so a screenshot would record whichever slot the user last hovered. The
        // pointer
        // is not drawn into the framebuffer, so parking it in a corner only removes that incidental
        // state. See #124. The game re-reads the pointer every frame, so the shared class has to be
        // called every frame, not once when the run starts.
        if (testRunner != null && !Mouse.isGrabbed()) {
            window.parkCursor();
        }

        // 1.2.5 renders RenderGlobal.tileEntities without checking isInvalid(), and only
        // reconciles that list when a chunk's WorldRenderer rebuilds, so a chest replaced by
        // another block can still be rendered until that rebuild. While the game is paused the
        // loaded list has the same problem, because a paused world skips World.updateEntities but
        // keeps rendering. Dropping them at the start of the render tick leaves them out of the
        // frame that is about to be drawn. See #114.
        if (minecraft.theWorld != null) {
            dropInvalidTileEntities(minecraft.theWorld.loadedTileEntityList);
        }
        if (minecraft.renderGlobal != null) {
            dropInvalidTileEntities(minecraft.renderGlobal.tileEntities);
        }
    }

    /**
     * Resizes the game window.
     *
     * <p>1.2.5 parents the LWJGL Display to an AWT canvas, so {@code Display.setDisplayMode} alone
     * is not enough; the top-level Window has to be resized and the layout then sizes the canvas.
     * The game re-reads the canvas size each frame, so displayWidth/Height follow.
     */
    private static void applyDisplayMode(DisplayMode mode) {
        try {
            Display.setDisplayMode(mode);

            if (mod_Grug.minecraft() != null && mod_Grug.minecraft().mcCanvas != null) {
                mod_Grug.minecraft()
                        .mcCanvas
                        .setPreferredSize(new Dimension(mode.getWidth(), mode.getHeight()));
                mod_Grug.minecraft().mcCanvas.setSize(mode.getWidth(), mode.getHeight());

                Window window = enclosingWindow(mod_Grug.minecraft().mcCanvas);
                if (window != null) {
                    Insets insets = window.getInsets();
                    window.setSize(
                            mode.getWidth() + insets.left + insets.right,
                            mode.getHeight() + insets.top + insets.bottom);
                    window.validate();
                }
            }
        } catch (Exception e) {
            // Bounded: the window is the size it was, the run carries on, and the failure is
            // reported the way any other bounded defect is, which fails the run rather than leaving
            // a screenshot taken at some other resolution than the tests asked for.
            GrugModTreeDefect.report(
                    "Failed to switch the window to "
                            + mode.getWidth()
                            + "x"
                            + mode.getHeight()
                            + ": "
                            + e);
        }
    }

    private static Window enclosingWindow(Component component) {
        for (Container ancestor = component.getParent();
                ancestor != null;
                ancestor = ancestor.getParent()) {
            if (ancestor instanceof Window) {
                return (Window) ancestor;
            }
        }
        return null;
    }

    /** Last cursor position printed to chat, or Integer.MIN_VALUE when nothing has been printed. */
    private int lastCursorX = Integer.MIN_VALUE;

    private int lastCursorY = Integer.MIN_VALUE;

    /**
     * Reads the mouse position in the same top-left-origin convention Screenshot.equals() takes, so
     * a coordinate read off chat can be pasted straight into a crop rectangle. Called every tick
     * while M holds the window at the test resolution, but only prints when the position changed,
     * so a stationary mouse doesn't fill chat.
     *
     * <p>LWJGL's Mouse counts Y from the bottom of the window, like GL does, whereas the crop
     * rectangle counts from the top, so the Y is flipped against the display height.
     */
    @GrugGenerated("dev-only: cursor-coordinate readout")
    private void updateCursorReadout(Minecraft minecraft) {
        if (!window.resolutionForced() || testRunner != null) {
            // Forget the last position while inactive, so the readout reappears the moment M is
            // switched back on even if the cursor hasn't moved since it was last on.
            lastCursorX = Integer.MIN_VALUE;
            lastCursorY = Integer.MIN_VALUE;
            return;
        }

        // Only meaningful at the test resolution, with a free cursor. While the mouse is grabbed
        // for camera control Mouse.getX/Y accumulate movement deltas rather than pointing at a
        // pixel, so printing them would just fill chat with drift. A screen being open is what
        // ungrabs it.
        if (minecraft.displayWidth != GrugScreenshots.WIDTH
                || minecraft.displayHeight != GrugScreenshots.HEIGHT
                || Mouse.isGrabbed()) {
            lastCursorX = Integer.MIN_VALUE;
            lastCursorY = Integer.MIN_VALUE;
            return;
        }

        int screenX = Mouse.getX();
        int screenY = minecraft.displayHeight - Mouse.getY();

        if (screenX == lastCursorX && screenY == lastCursorY) {
            return;
        }

        lastCursorX = screenX;
        lastCursorY = screenY;
        sendMessage("Cursor: " + screenX + ", " + screenY, "");
    }

    /** Logs a contained mod err and mirrors it to chat in red. */
    private static void logError(String text) {
        // A contained mod err from a runtime error handler, and the sandbox's own report of it: the
        // JNI layer clears whatever a script threw and the script carries on, so this is where a
        // failed script call surfaces.
        mod_Grug.LOGGER.severe(text);
        sendChat(text, "\u00A7c");
    }

    /** Logs each line of a grug message and mirrors it to chat, once a player is in the world. */
    private static void sendMessage(String text, String prefix) {
        if (ModLoader.getMinecraftInstance().thePlayer == null || text == null) return;

        for (String line : text.split("\n")) {
            mod_Grug.LOGGER.info(prefix + line);
        }
        sendChat(text, prefix);
    }

    /** Mirrors a grug message into the in-game chat, wrapped at 50 characters per line. */
    private static void sendChat(String text, String prefix) {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        if (minecraft.thePlayer == null || text == null) return;

        // A CI run is not watched, and the readout is drawn into every capture that reaches
        // down to it, so the log is all it gets. See #412.
        if (GrugReference.isCiRun()) return;

        for (String rawLine : text.split("\n")) {
            String line = rawLine;
            int maxLength = 50;
            while (line.length() > maxLength) {
                int splitIndex = line.lastIndexOf(' ', maxLength);
                if (splitIndex == -1) {
                    splitIndex = maxLength;
                }
                minecraft.ingameGUI.addChatMessage(prefix + line.substring(0, splitIndex));
                line = line.substring(splitIndex).trim();
            }
            if (!line.isEmpty()) {
                minecraft.ingameGUI.addChatMessage(prefix + line);
            }
        }
    }

    /**
     * Drops invalidated tile entities from a list.
     *
     * <p>1.2.5's {@code RenderGlobal.renderEntities} renders every entry of {@code
     * RenderGlobal.tileEntities} without checking {@code isInvalid()}, and that list is only
     * reconciled when a chunk's {@code WorldRenderer} rebuilds. A chest replaced by another block
     * can therefore still be rendered until that rebuild, and {@code TileEntityChestRenderer} then
     * casts the replacement block to a chest and crashes. A paused world has the same problem for
     * {@code World.loadedTileEntityList}, because it skips {@code World.updateEntities} while still
     * rendering. See #114.
     */
    private static void dropInvalidTileEntities(List list) {
        Iterator iterator = list.iterator();
        while (iterator.hasNext()) {
            Object entry = iterator.next();
            if (entry instanceof TileEntity && ((TileEntity) entry).isInvalid()) {
                iterator.remove();
            }
        }
    }
}
