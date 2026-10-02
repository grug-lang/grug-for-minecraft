package net.grug.minecraft.forge125.client;

import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.forge125.mod_Grug;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.minecraft.client.Minecraft;
import net.minecraft.src.GuiMainMenu;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.PlayerControllerSP;
import net.minecraft.src.TileEntity;
import net.minecraft.src.WorldSettings;
import net.minecraft.src.WorldType;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Window;
import java.util.Iterator;
import java.util.List;

/**
 * The 1.2.5 client's per-tick work, driven from {@code mod_Grug.onTickInGame}.
 *
 * <p>1.2.5 has no Mixin, so this is a ModLoader hook rather than an injected method. It exists as
 * its own class so the runner logic stays out of the entry point, matching how the other loaders
 * keep their tick logic in {@code net.grug.*}.
 */
public class GrugClientHooks {

    private boolean titleLogged = false;
    private boolean worldRequested = false;
    private boolean ciTestsRan = false;

    private GrugTestRunner testRunner = null;
    private boolean testRunnerFromCI = false;

    /** Non-null exactly while the window is forced to the screenshot resolution. */
    private DisplayMode savedDisplayMode = null;

    /** Whether GL_DITHER was on before a run turned it off, so it can be put back. */
    private boolean ditherWasEnabled = false;

    public void tick(Minecraft minecraft) {
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

        if ("true".equals(System.getenv("GRUG_CI")) && !ciTestsRan && minecraft.thePlayer != null) {
            System.out.println("[GRUG CI] CI mode active & player loaded. Firing tests!");
            ciTestsRan = true;
            startTestRunner(true);
        }

        // One Test.run() call per real tick, so real ticks separate a test's invocations.
        if (testRunner != null) {
            testRunner.tick(minecraft.thePlayer);
            if (testRunner.isFinished()) {
                boolean fromCI = testRunnerFromCI;
                testRunner = null;
                testRunnerFromCI = false;
                finishTestRun();
                if (fromCI) {
                    minecraft.shutdownMinecraftApplet();
                }
            }
        }

        String[] updatedResources = Grug.update(this::logError);
        if (updatedResources.length > 0 && minecraft.renderEngine != null) {
            minecraft.renderEngine.refreshTextures();
        }

        // 1.2.5 renders RenderGlobal.tileEntities without checking isInvalid(), and only
        // reconciles that list when a chunk's WorldRenderer rebuilds, so a chest replaced by
        // another block can still be rendered until that rebuild. While the game is paused the
        // loaded list has the same problem, because a paused world skips World.updateEntities but
        // keeps rendering. Drop them before the frame renders. See #114.
        if (minecraft.theWorld != null) {
            dropInvalidTileEntities(minecraft.theWorld.loadedTileEntityList);
        }
        if (minecraft.renderGlobal != null) {
            dropInvalidTileEntities(minecraft.renderGlobal.tileEntities);
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

    @GrugGenerated("test-run window and dither tooling")
    private void startTestRunner(boolean fromCI) {
        if (testRunner != null) {
            return;
        }

        if (savedDisplayMode == null) {
            savedDisplayMode = new DisplayMode(Display.getWidth(), Display.getHeight());
            applyDisplayMode(new DisplayMode(GrugScreenshots.WIDTH, GrugScreenshots.HEIGHT));
        }

        // OpenGL dithers by default, which puts +/-1 noise on a GUI-sized crop and moves it around
        // between runs, which is the difference between a pixel-exact comparison and one that can
        // never pass.
        ditherWasEnabled = GL11.glIsEnabled(GL11.GL_DITHER);
        GL11.glDisable(GL11.GL_DITHER);

        testRunner = new GrugTestRunner();
        testRunnerFromCI = fromCI;
    }

    @GrugGenerated("test-run window and dither tooling")
    private void finishTestRun() {
        if (savedDisplayMode != null) {
            DisplayMode previous = savedDisplayMode;
            savedDisplayMode = null;
            applyDisplayMode(previous);
        }
        if (ditherWasEnabled) {
            GL11.glEnable(GL11.GL_DITHER);
        }
        ditherWasEnabled = false;
    }

    /**
     * Resizes the game window.
     *
     * <p>1.2.5 parents the LWJGL Display to an AWT canvas, so {@code Display.setDisplayMode} alone
     * is not enough; the top-level Window has to be resized and the layout then sizes the canvas.
     * The game re-reads the canvas size each frame, so displayWidth/Height follow.
     */
    @GrugGenerated("test-run window and dither tooling")
    private void applyDisplayMode(DisplayMode mode) {
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

    @GrugGenerated("test-run window and dither tooling")
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

    private void logError(String text) {
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
