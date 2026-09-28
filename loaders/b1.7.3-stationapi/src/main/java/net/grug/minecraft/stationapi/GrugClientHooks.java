package net.grug.minecraft.stationapi;

import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.stationapi.events.init.ClientInitListener;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.minecraft.client.Minecraft;
import net.modificationstation.stationapi.api.client.resource.ReloadableAssetsManager;
import net.modificationstation.stationapi.api.tick.TickScheduler;
import net.modificationstation.stationapi.api.util.Util;
import net.modificationstation.stationapi.impl.client.resource.AssetsReloaderImpl;

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

/**
 * The Minecraft client's per-tick behavior, driven from the {@code MinecraftMixin} injection.
 *
 * <p>This lives in {@code net.grug.*} because Mixin never defines a mixin class: it copies the
 * mixin's methods into the target, so JaCoCo can neither instrument the mixin class nor attribute
 * its probes. Keeping the logic here makes it measurable. The mixin forwards to this class and
 * implements {@link GrugClientAccess} for the few members that are private or version-specific.
 */
public class GrugClientHooks {
    private final Minecraft minecraft;
    private final GrugClientAccess access;
    private final String title;

    private boolean titleSet = false;
    private boolean testsKeyPressed = false;
    private boolean resolutionKeyPressed = false;

    /** Last cursor position printed to chat, or Integer.MIN_VALUE when nothing has been printed. */
    private int lastCursorX = Integer.MIN_VALUE;

    private int lastCursorY = Integer.MIN_VALUE;

    /** Cursor position before a run parked it in a corner, so it can be put back afterwards. */
    private int savedCursorX = 0;

    private int savedCursorY = 0;
    private boolean cursorParked = false;
    private boolean ciTestsRan = false;
    private GrugTestRunner testRunner = null;
    private boolean testRunnerFromCI = false;

    /** Non-null exactly while the window is forced to 1280x720, whoever forced it. */
    private DisplayMode savedDisplayMode = null;

    /** True only while R (not M) is the reason the window is forced, so only R restores it. */
    private boolean resolutionForcedByTestRun = false;

    /** Whether GL_DITHER was on before a test run turned it off, so it can be put back. */
    private boolean ditherWasEnabled = false;

    public GrugClientHooks(Minecraft minecraft, GrugClientAccess access, String title) {
        this.minecraft = minecraft;
        this.access = access;
        this.title = title;
    }

    public void tick() {
        if (!titleSet) {
            Display.setTitle(title);
            titleSet = true;
        }

        // CI auto-execution
        if ("true".equals(System.getenv("GRUG_CI")) && !ciTestsRan && minecraft.player != null) {
            System.out.println("[GRUG CI] CI mode active & player loaded. Firing tests!");
            ciTestsRan = true;
            startTestRunner(true);
        }

        // The runner does one Test.run() call per real tick, so that real ticks (and real rendered
        // frames) elapse between a test's own invocations.
        if (testRunner != null) {
            testRunner.tick(minecraft.player);
            if (testRunner.isFinished()) {
                boolean fromCI = testRunnerFromCI;
                testRunner = null;
                testRunnerFromCI = false;
                finishTestRun();
                // The hotkey path leaves the game running so another R press can start a fresh run.
                if (fromCI) {
                    access.grug$shutdown();
                }
            }
        }

        // Test runner hotkey logic
        if (ClientInitListener.runTestsKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(ClientInitListener.runTestsKey.code);
            if (isKeyDown && !testsKeyPressed) {
                testsKeyPressed = true;
                startTestRunner(false);
            } else if (!isKeyDown) {
                testsKeyPressed = false;
            }
        }

        // M: force the window to the resolution screenshot tests are captured at
        if (ClientInitListener.forceResolutionKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(ClientInitListener.forceResolutionKey.code);
            if (isKeyDown && !resolutionKeyPressed) {
                resolutionKeyPressed = true;
                if (savedDisplayMode == null) {
                    savedDisplayMode = currentWindowDisplayMode();
                    applyTestDisplayMode();
                } else {
                    DisplayMode previous = savedDisplayMode;
                    savedDisplayMode = null;
                    resolutionForcedByTestRun = false;
                    applyDisplayMode(previous);
                }
            } else if (!isKeyDown) {
                resolutionKeyPressed = false;
            }
        }

        // While M is holding the window at the test resolution, keep the cursor's screenshot-crop
        // coordinates in chat. A test run is excluded because it forces the same resolution for its
        // own reasons and nobody is aiming a cursor at it.
        if (savedDisplayMode != null && testRunner == null) {
            updateCursorPositionReadout();
        } else {
            // Forget the last position while inactive, so the readout reappears the moment M is
            // switched back on even if the cursor hasn't moved since it was last on.
            lastCursorX = Integer.MIN_VALUE;
            lastCursorY = Integer.MIN_VALUE;
        }

        // A captured frame must not depend on where the invisible cursor happens to be: the game
        // highlights the slot under the mouse and tooltips follow it, so park the cursor in a
        // corner outside the centered GUI for the duration of a run. The cursor isn't drawn into
        // the framebuffer, so this only removes that incidental state.
        if (testRunner != null && !Mouse.isGrabbed()) {
            parkCursor();
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
                    (reloader, formatString, location) -> {}, // No-op profiler
                    AssetsReloaderImpl.RESOURCE_PACK_MANAGER.createResourcePacks());
        }

        if (minecraft.player != null) {
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

    private void startTestRunner(boolean fromCI) {
        // Pressing the hotkey again while a run is still going shouldn't restart it.
        if (testRunner != null) {
            return;
        }

        // Screenshot tests only compare equal at the resolution their reference was captured at,
        // so a run always happens at 1280x720 and the previous resolution comes back afterwards.
        // If M already forced that size, leave its state alone: this run didn't set it up, so it
        // shouldn't undo it, and a second M press remains the way back.
        if (savedDisplayMode == null) {
            savedDisplayMode = currentWindowDisplayMode();
            applyTestDisplayMode();
            resolutionForcedByTestRun = true;
        }

        // OpenGL dithers by default, which puts +/-1 noise on a GUI-sized crop and moves it around
        // between runs. That's the difference between a pixel-exact comparison and one that can
        // never pass, so turn it off for the duration of the run.
        ditherWasEnabled = GL11.glIsEnabled(GL11.GL_DITHER);
        GL11.glDisable(GL11.GL_DITHER);

        testRunner = new GrugTestRunner();
        testRunnerFromCI = fromCI;
    }

    /**
     * Puts the window and the GL state back the way this run found them, if this run changed them.
     */
    private void finishTestRun() {
        if (resolutionForcedByTestRun) {
            DisplayMode previous = savedDisplayMode;
            savedDisplayMode = null;
            resolutionForcedByTestRun = false;
            applyDisplayMode(previous);
        }
        if (ditherWasEnabled) {
            GL11.glEnable(GL11.GL_DITHER);
        }
        ditherWasEnabled = false;

        if (cursorParked) {
            cursorParked = false;
            Mouse.setCursorPosition(savedCursorX, savedCursorY);
        }
    }

    /**
     * Moves the cursor to the window's corner while a screen is open, so a screenshot doesn't
     * record the slot-hover highlight. The window-corner position is outside every centered GUI
     * panel, and the cursor itself is never drawn into the framebuffer.
     */
    private void parkCursor() {
        if (!cursorParked) {
            savedCursorX = Mouse.getX();
            savedCursorY = Mouse.getY();
            cursorParked = true;
        }
        if (Mouse.getX() != 0 || Mouse.getY() != 0) {
            // LWJGL's Mouse uses OpenGL window coordinates (origin bottom-left), so (0,0) is a
            // corner. The game re-reads it every poll, so this has to be reapplied each tick.
            Mouse.setCursorPosition(0, 0);
        }
    }

    /** Switches the window to the resolution screenshot tests are captured at. */
    private void applyTestDisplayMode() {
        applyDisplayMode(new DisplayMode(GrugScreenshots.WIDTH, GrugScreenshots.HEIGHT));
    }

    /**
     * The size the game window is actually at.
     *
     * <p>Deliberately not {@code Display.getDisplayMode()}. This generation of the game parents the
     * LWJGL Display to an AWT Canvas, and in that mode the Display inherits the size of the parent
     * and {@code getDisplayMode()} keeps reporting the mode it was created with (the desktop
     * resolution). {@code Display.getWidth()/getHeight()} report the canvas size here.
     */
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
    private void applyDisplayMode(DisplayMode mode) {
        try {
            Display.setDisplayMode(mode);

            if (minecraft.canvas != null) {
                minecraft.canvas.setPreferredSize(new Dimension(mode.getWidth(), mode.getHeight()));
                minecraft.canvas.setSize(mode.getWidth(), mode.getHeight());

                Window window = enclosingWindow(minecraft.canvas);
                if (window != null) {
                    Insets insets = window.getInsets();
                    window.setSize(
                            mode.getWidth() + insets.left + insets.right,
                            mode.getHeight() + insets.top + insets.bottom);
                    window.validate();
                }
            }

            access.grug$resize(mode.getWidth(), mode.getHeight());
        } catch (Exception e) {
            // Resolution changes are finicky across platforms, so report rather than crash the
            // client.
            InitListener.LOGGER.error(
                    "Failed to switch the window to " + mode.getWidth() + "x" + mode.getHeight(),
                    e);
            sendRedMessage(
                    "Failed to switch the window to "
                            + mode.getWidth()
                            + "x"
                            + mode.getHeight()
                            + ".");
        }
    }

    /** The Window a component lives in, however many containers deep it is. */
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

    /**
     * Reads the mouse position in the same top-left-origin convention
     * Test.assert_screenshot_equals() takes, so a coordinate read off chat can be pasted straight
     * into a crop rectangle. Called every tick while M holds the window at the test resolution, but
     * only prints when the position changed, so a stationary mouse doesn't fill chat.
     *
     * <p>This shares its flip with captureRectangle() in StationApiAdapter: LWJGL's Mouse counts Y
     * from the bottom of the window, like GL does, whereas the crop rectangle counts from the top.
     */
    private void updateCursorPositionReadout() {
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

    private void sendRedMessage(String text) {
        sendMessage(text, "\u00A7c");
    }

    private void sendMessage(String text, String prefix) {
        if (minecraft.player == null || text == null) return;

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
                access.grug$sendChat(prefix + line.substring(0, splitIndex));
                line = line.substring(splitIndex).trim(); // Remove leading space for the next line
            }
            if (!line.isEmpty()) {
                access.grug$sendChat(prefix + line);
            }
        }
    }
}
