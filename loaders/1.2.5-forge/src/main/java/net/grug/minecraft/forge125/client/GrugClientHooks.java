package net.grug.minecraft.forge125.client;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugRunWindow;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.forge125.mod_Grug;
import net.grug.minecraft.grug.Grug;
import net.minecraft.client.Minecraft;
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
                public int cursorX() {
                    return Mouse.getX();
                }

                @Override
                public int cursorY() {
                    return Mouse.getY();
                }

                @Override
                public void setCursor(int x, int y) {
                    Mouse.setCursorPosition(x, y);
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

    @Override
    public void tickStart(EnumSet<TickType> types, Object... data) {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        if (types.contains(TickType.GAME)) {
            gameTick(minecraft);
        }
        if (types.contains(TickType.RENDER)) {
            renderTick(minecraft);
        }
    }

    @Override
    public void tickEnd(EnumSet<TickType> types, Object... data) {
        // Everything runs at tick start, like the injected client tick hooks on the other loaders.
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
     * The game tick, which is where the test runner is driven: one {@code Test.run()} call per game
     * tick, the cadence the other four loaders use.
     */
    private void gameTick(Minecraft minecraft) {
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

        // One Test.run() call per game tick, so ticks separate a test's invocations.
        if (testRunner != null) {
            testRunner.tick(minecraft.thePlayer);
            if (testRunner.isFinished()) {
                boolean fromCI = testRunnerFromCI;
                testRunner = null;
                testRunnerFromCI = false;
                window.endRun();
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
                testRunner = new GrugTestRunner();
                testRunnerFromCI = false;
            }
        }

        String[] updatedResources = Grug.update(GrugClientHooks::logError);
        if (updatedResources.length > 0 && minecraft.renderEngine != null) {
            minecraft.renderEngine.refreshTextures();
        }

        if (minecraft.thePlayer != null) {
            synchronized (Grug.runtimeErrorQueue) {
                while (!Grug.runtimeErrorQueue.isEmpty()) {
                    logError(Grug.runtimeErrorQueue.poll());
                }
            }
            synchronized (Grug.printQueue) {
                while (!Grug.printQueue.isEmpty()) {
                    mod_Grug.LOGGER.info(Grug.printQueue.poll());
                }
            }
        }
    }

    /** The frame, for the work that has to happen before one is drawn. */
    private void renderTick(Minecraft minecraft) {
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
            logError("Failed to switch the window to " + mode.getWidth() + "x" + mode.getHeight());
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

    private static void logError(String text) {
        mod_Grug.LOGGER.severe(text);
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
