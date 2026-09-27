package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.stationapi.events.init.ClientInitListener;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.modificationstation.stationapi.api.client.resource.ReloadableAssetsManager;
import net.modificationstation.stationapi.api.tick.TickScheduler;
import net.modificationstation.stationapi.api.util.Util;
import net.modificationstation.stationapi.impl.client.resource.AssetsReloaderImpl;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Window;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    public ClientPlayerEntity player;

    @Shadow
    public abstract void scheduleStop();

    // Minecraft.resize() is private, so it can't be called directly. Java forbids "private
    // abstract", and a shadow only has to be at least as visible as its target, so this is
    // declared protected.
    @Shadow
    protected abstract void resize(int width, int height);

    @Unique
    private boolean grug$titleSet = false;

    @Unique
    private boolean grug$testsKeyPressed = false;

    @Unique
    private boolean grug$resolutionKeyPressed = false;

    /** Last cursor position printed to chat, or Integer.MIN_VALUE when nothing has been printed. */
    @Unique
    private int grug$lastCursorX = Integer.MIN_VALUE;

    @Unique
    private int grug$lastCursorY = Integer.MIN_VALUE;

    @Unique
    private boolean grug$ciTestsRan = false;

    @Unique
    private GrugTestRunner grug$testRunner = null;

    @Unique
    private boolean grug$testRunnerFromCI = false;

    /** Non-null exactly while the window is forced to 1280x720, whoever forced it. */
    @Unique
    private DisplayMode grug$savedDisplayMode = null;

    /** True only while R (not M) is the reason the window is forced, so only R restores it. */
    @Unique
    private boolean grug$resolutionForcedByTestRun = false;

    /** Whether GL_DITHER was on before a test run turned it off, so it can be put back. */
    @Unique
    private boolean grug$ditherWasEnabled = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTick(CallbackInfo ci) {
        if (!this.grug$titleSet) {
            Display.setTitle("Minecraft Beta 1.7.3 - StationAPI with grug");
            this.grug$titleSet = true;
        }

        // CI auto-execution
        if ("true".equals(System.getenv("GRUG_CI")) && !this.grug$ciTestsRan && this.player != null) {
            System.out.println("[GRUG CI] CI mode active & player loaded. Firing tests!");
            this.grug$ciTestsRan = true;
            this.startTestRunner(true);
        }

        // The runner does one Test.run() call per real tick, so that real ticks (and real rendered
        // frames) elapse between a test's own invocations.
        if (this.grug$testRunner != null) {
            this.grug$testRunner.tick(this.player);
            if (this.grug$testRunner.isFinished()) {
                boolean fromCI = this.grug$testRunnerFromCI;
                this.grug$testRunner = null;
                this.grug$testRunnerFromCI = false;
                this.finishTestRun();
                // The hotkey path leaves the game running so another R press can start a fresh run.
                if (fromCI) {
                    this.scheduleStop();
                }
            }
        }

        // Test runner hotkey logic
        if (ClientInitListener.runTestsKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(ClientInitListener.runTestsKey.code);
            if (isKeyDown && !this.grug$testsKeyPressed) {
                this.grug$testsKeyPressed = true;
                this.startTestRunner(false);
            } else if (!isKeyDown) {
                this.grug$testsKeyPressed = false;
            }
        }

        // M: force the window to the resolution screenshot tests are captured at
        if (ClientInitListener.forceResolutionKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(ClientInitListener.forceResolutionKey.code);
            if (isKeyDown && !this.grug$resolutionKeyPressed) {
                this.grug$resolutionKeyPressed = true;
                if (this.grug$savedDisplayMode == null) {
                    this.grug$savedDisplayMode = currentWindowDisplayMode();
                    this.applyTestDisplayMode();
                } else {
                    DisplayMode previous = this.grug$savedDisplayMode;
                    this.grug$savedDisplayMode = null;
                    this.grug$resolutionForcedByTestRun = false;
                    this.applyDisplayMode(previous);
                }
            } else if (!isKeyDown) {
                this.grug$resolutionKeyPressed = false;
            }
        }

        // While M is holding the window at the test resolution, keep the cursor's screenshot-crop
        // coordinates in chat. A test run is excluded because it forces the same resolution for its
        // own reasons and nobody is aiming a cursor at it.
        if (this.grug$savedDisplayMode != null && this.grug$testRunner == null) {
            this.updateCursorPositionReadout();
        } else {
            // Forget the last position while inactive, so the readout reappears the moment M is
            // switched back on even if the cursor hasn't moved since it was last on.
            this.grug$lastCursorX = Integer.MIN_VALUE;
            this.grug$lastCursorY = Integer.MIN_VALUE;
        }

        // Trigger resource reloading for ANY non-grug file change in the mods directory
        // (textures, blockstates, models, lang files, etc.)
        String[] updatedResources = Grug.update(this::sendRedMessage);
        for (String resource : updatedResources) {
            InitListener.LOGGER.info("Reloading changed resource: {}", resource);
            InitListener.handlePossibleRecipeUpdate(resource);
        }

        if (updatedResources.length > 0) {
            // Bypass StationAPI's ReloadScreenManager entirely to avoid the blue overlay
            // and Escape bug.
            AssetsReloaderImpl.RESOURCE_PACK_MANAGER.scanPacks();
            ReloadableAssetsManager.INSTANCE.reload(
                    Util.getMainWorkerExecutor(),
                    TickScheduler.CLIENT_RENDER_END::distributed,
                    AssetsReloaderImpl.COMPLETED_UNIT_FUTURE,
                    (reloader, formatString, location) -> {
                    }, // No-op profiler
                    AssetsReloaderImpl.RESOURCE_PACK_MANAGER.createResourcePacks());
        }

        if (this.player != null) {
            // Handle runtime errors triggered by grug-rs
            synchronized (Grug.runtimeErrorQueue) {
                while (!Grug.runtimeErrorQueue.isEmpty()) {
                    sendRedMessage(Grug.runtimeErrorQueue.poll());
                }
            }

            // Handle print statements
            synchronized (Grug.printQueue) {
                while (!Grug.printQueue.isEmpty()) {
                    sendMessage(Grug.printQueue.poll(), "");
                }
            }
        }
    }

    @Unique
    private void startTestRunner(boolean fromCI) {
        // Pressing the hotkey again while a run is still going shouldn't restart it.
        if (this.grug$testRunner != null) {
            return;
        }

        // Screenshot tests only compare equal at the resolution their reference was captured at,
        // so a run always happens at 1280x720 and the previous resolution comes back afterwards.
        // If M already forced that size, leave its state alone: this run didn't set it up, so it
        // shouldn't undo it, and a second M press remains the way back.
        if (this.grug$savedDisplayMode == null) {
            this.grug$savedDisplayMode = currentWindowDisplayMode();
            this.applyTestDisplayMode();
            this.grug$resolutionForcedByTestRun = true;
        }

        // OpenGL dithers by default, which puts +/-1 noise on a GUI-sized crop and moves it around
        // between runs. That's the difference between a pixel-exact comparison and one that can
        // never pass, so turn it off for the duration of the run.
        this.grug$ditherWasEnabled = GL11.glIsEnabled(GL11.GL_DITHER);
        GL11.glDisable(GL11.GL_DITHER);

        this.grug$testRunner = new GrugTestRunner();
        this.grug$testRunnerFromCI = fromCI;
    }

    /** Puts the window and the GL state back the way this run found them, if this run changed them. */
    @Unique
    private void finishTestRun() {
        if (this.grug$resolutionForcedByTestRun) {
            DisplayMode previous = this.grug$savedDisplayMode;
            this.grug$savedDisplayMode = null;
            this.grug$resolutionForcedByTestRun = false;
            this.applyDisplayMode(previous);
        }
        if (this.grug$ditherWasEnabled) {
            GL11.glEnable(GL11.GL_DITHER);
        }
        this.grug$ditherWasEnabled = false;
    }

    /** Switches the window to the resolution screenshot tests are captured at. */
    @Unique
    private void applyTestDisplayMode() {
        this.applyDisplayMode(new DisplayMode(GrugScreenshots.WIDTH, GrugScreenshots.HEIGHT));
    }

    /**
     * The size the game window is actually at.
     *
     * <p>Deliberately not {@code Display.getDisplayMode()}. This generation of the game parents the
     * LWJGL Display to an AWT Canvas, and in that mode the Display inherits the size of the parent
     * and {@code getDisplayMode()} keeps reporting the mode it was created with (the desktop
     * resolution). {@code Display.getWidth()/getHeight()} report the canvas size here.
     */
    @Unique
    private static DisplayMode currentWindowDisplayMode() {
        return new DisplayMode(Display.getWidth(), Display.getHeight());
    }

    /**
     * Resizes the game window to the given mode.
     *
     * <p>{@code Display.setDisplayMode()} alone isn't enough: it's a no-op while the Display is
     * parented to the Canvas, and the Canvas's size is owned by the layout of whatever contains it.
     * The top-level Window has to be resized, and the layout then sizes everything below it. The
     * Canvas is deliberately not assumed to be a direct child of a Frame; the launcher may wrap it.
     */
    @Unique
    private void applyDisplayMode(DisplayMode mode) {
        try {
            Display.setDisplayMode(mode);

            Minecraft mc = (Minecraft) (Object) this;
            if (mc.canvas != null) {
                mc.canvas.setPreferredSize(new Dimension(mode.getWidth(), mode.getHeight()));
                mc.canvas.setSize(mode.getWidth(), mode.getHeight());

                Window window = enclosingWindow(mc.canvas);
                if (window != null) {
                    Insets insets = window.getInsets();
                    window.setSize(mode.getWidth() + insets.left + insets.right,
                            mode.getHeight() + insets.top + insets.bottom);
                    window.validate();
                }
            }

            this.resize(mode.getWidth(), mode.getHeight());
        } catch (Exception e) {
            // Resolution changes are finicky across platforms, so report rather than crash the client.
            InitListener.LOGGER.error("Failed to switch the window to "
                    + mode.getWidth() + "x" + mode.getHeight(), e);
            sendRedMessage("Failed to switch the window to " + mode.getWidth() + "x" + mode.getHeight() + ".");
        }
    }

    /** The Window a component lives in, however many containers deep it is. */
    @Unique
    private static Window enclosingWindow(Component component) {
        for (Container ancestor = component.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor instanceof Window) {
                return (Window) ancestor;
            }
        }
        return null;
    }

    /**
     * Reads the mouse position in the same top-left-origin convention
     * Test.assert_screenshot_equals() takes, so a coordinate read off chat can be pasted straight
     * into a crop rectangle. Called every tick while M holds the window at the test resolution, but
     * only prints when the position changed, so a stationary mouse doesn't fill chat.
     *
     * <p>This shares its flip with captureRectangle() in StationApiAdapter: LWJGL's Mouse counts Y
     * from the bottom of the window, like GL does, whereas the crop rectangle counts from the top.
     */
    @Unique
    private void updateCursorPositionReadout() {
        Minecraft mc = (Minecraft) (Object) this;

        // Only meaningful at the test resolution, with a free cursor. While the mouse is grabbed for
        // camera control Mouse.getX/Y accumulate movement deltas rather than pointing at a pixel, so
        // printing them would just fill chat with drift. A screen being open is what ungrabs it.
        if (mc.displayWidth != GrugScreenshots.WIDTH
                || mc.displayHeight != GrugScreenshots.HEIGHT
                || Mouse.isGrabbed()) {
            this.grug$lastCursorX = Integer.MIN_VALUE;
            this.grug$lastCursorY = Integer.MIN_VALUE;
            return;
        }

        int screenX = Mouse.getX();
        int screenY = mc.displayHeight - Mouse.getY();

        if (screenX == this.grug$lastCursorX && screenY == this.grug$lastCursorY) {
            return;
        }

        this.grug$lastCursorX = screenX;
        this.grug$lastCursorY = screenY;
        sendMessage("Cursor: " + screenX + ", " + screenY, "");
    }

    @Unique
    private void sendRedMessage(String text) {
        sendMessage(text, "\u00A7c");
    }

    @Unique
    private void sendMessage(String text, String prefix) {
        if (this.player == null || text == null)
            return;

        String[] lines = text.split("\n");
        for (String line : lines) {
            // Print the full, unsplit line to the console
            InitListener.LOGGER.info(prefix + line);

            // Break long lines into chunks of ~50 characters
            // so the color code is preserved for the player
            int maxLength = 50;
            while (line.length() > maxLength) {
                int splitIndex = line.lastIndexOf(' ', maxLength);
                if (splitIndex == -1) {
                    splitIndex = maxLength; // Force split mid-word if there are no spaces
                }
                this.player.sendMessage(prefix + line.substring(0, splitIndex));
                line = line.substring(splitIndex).trim(); // Remove leading space for the next line
            }
            if (!line.isEmpty()) {
                this.player.sendMessage(prefix + line);
            }
        }
    }
}
