package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugRunWindow;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;
import net.ornithemc.osl.keybinds.api.KeybindEvents;
import net.ornithemc.osl.keybinds.api.KeybindRegistry;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Window;

/**
 * The Minecraft client's per-tick behavior, driven from the {@code MinecraftMixin} injection.
 *
 * <p>This lives in {@code net.grug.*} because Mixin never defines a mixin class: it copies the
 * mixin's methods into the target, so JaCoCo can neither instrument the mixin class nor attribute
 * its probes. Keeping the logic here makes it measurable. The mixin forwards to this class and
 * implements {@link GrugClientAccess} for the few members that are private or version-specific.
 *
 * <p>Only the Ornithe-specific glue is measured-out here: which LWJGL, AWT and GL calls do a
 * resize, and how a key poll reads. The save/force/restore transitions behind the R and M keys live
 * in core's {@link GrugRunWindow}, which core's JUnit tests measure.
 */
public class GrugClientHooks {
    private static KeyBinding runTestsKey;
    private static KeyBinding forceResolutionKey;

    private final Minecraft minecraft;
    private final GrugClientAccess access;
    private final String title;

    private boolean titleSet = false;
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
     * The Ornithe half of the shared run state machine: LWJGL's Display, the AWT canvas it is
     * parented to, GL dither and the mouse. The save/force/restore transitions themselves live in
     * {@link GrugRunWindow}, which is where they are measured.
     */
    private final GrugRunWindow.Display display =
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
                    applyDisplayMode(width, height);
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

    /**
     * The R and M keys in the consumption contract the shared state machine polls: a press is
     * reported once, on the first tick that sees the key down, and a poll consumes it.
     */
    private final GrugRunWindow.Keys keys =
            new GrugRunWindow.Keys() {
                private boolean runKeyDown = false;
                private boolean forceKeyDown = false;
                private boolean runQueued = false;
                private boolean forceQueued = false;

                @Override
                public boolean runPressed() {
                    boolean down = runTestsKey != null && Keyboard.isKeyDown(runTestsKey.keyCode);
                    if (down) {
                        if (!runKeyDown) {
                            runQueued = true;
                        }
                        runKeyDown = true;
                    } else {
                        runKeyDown = false;
                    }
                    boolean queued = runQueued;
                    runQueued = false;
                    return queued;
                }

                @Override
                public boolean forceResolutionPressed() {
                    boolean down =
                            forceResolutionKey != null
                                    && Keyboard.isKeyDown(forceResolutionKey.keyCode);
                    if (down) {
                        if (!forceKeyDown) {
                            forceQueued = true;
                        }
                        forceKeyDown = true;
                    } else {
                        forceKeyDown = false;
                    }
                    boolean queued = forceQueued;
                    forceQueued = false;
                    return queued;
                }
            };

    /**
     * The shared state machine for the run's window, dither and cursor, driven by the glue above.
     */
    private final GrugRunWindow window = new GrugRunWindow(display);

    public GrugClientHooks(Minecraft minecraft, GrugClientAccess access, String title) {
        this.minecraft = minecraft;
        this.access = access;
        this.title = title;
    }

    /** Registers the R and M keybinds; safe to call once from the mixin's static initializer. */
    @GrugGenerated("dev-only: R runs tests and M forces the test resolution by hand")
    public static void registerKeybinds() {
        KeybindEvents.REGISTER_KEYBINDS.register(
                () -> {
                    runTestsKey =
                            KeybindRegistry.register("key.grug.run_tests", Keyboard.KEY_R, "Grug");
                    forceResolutionKey =
                            KeybindRegistry.register(
                                    "key.grug.force_test_resolution", Keyboard.KEY_M, "Grug");
                });
    }

    public void tick() {
        if (!titleSet) {
            Display.setTitle(title);
            for (Frame frame : Frame.getFrames()) {
                frame.setTitle(title);
            }
            titleSet = true;
        }

        // CI auto-execution
        if ("true".equals(System.getenv("GRUG_CI")) && !ciTestsRan && minecraft.player != null) {
            // The runner starts as soon as the player exists, which can be before the client has
            // received the chunks the tests build in. Wait for them; a world that never arrives
            // fails the run instead of starting tests against placeholder chunks.
            if (ciWorldWaitStartMillis == 0) {
                ciWorldWaitStartMillis = System.currentTimeMillis();
            }
            boolean ready = GrugCore.getAdapter().isWorldReady(minecraft.player);
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

        // The runner does one Test.run() call per real tick, so that real ticks (and real rendered
        // frames) elapse between a test's own invocations.
        if (testRunner != null) {
            testRunner.tick(minecraft.player);
            if (testRunner.isFinished()) {
                boolean fromCI = testRunnerFromCI;
                testRunner = null;
                testRunnerFromCI = false;
                window.endRun();
                // The hotkey path leaves the game running so another R press can start a fresh run.
                if (fromCI) {
                    access.grug$shutdown();
                }
            }
        }

        // The polls consume the presses they report, so this has to run every tick a press is meant
        // to count. The tick it reports a run press in is the one that starts the run.
        if (window.tick(keys)) {
            testRunner = new GrugTestRunner();
            testRunnerFromCI = false;
        }

        updateCursorReadout();

        // A captured frame must not depend on where the invisible cursor happens to be: the game
        // highlights the slot under the mouse and tooltips follow it, so park the cursor in a
        // corner outside the centered GUI for the duration of a run. The cursor isn't drawn into
        // the framebuffer, so this only removes that incidental state.
        if (testRunner != null && !Mouse.isGrabbed()) {
            window.parkCursor();
        }

        String[] updatedResources = Grug.update(this::sendRedMessage);
        boolean reloadClientResources = false;

        for (String resource : updatedResources) {
            GrugModLoader.LOGGER.info("Reloading changed resource: {}", resource);
            reloadClientResources = true;
        }

        if (reloadClientResources) {
            minecraft.textureManager.reload();
        }

        if (minecraft.player != null) {
            synchronized (Grug.runtimeErrorQueue) {
                while (!Grug.runtimeErrorQueue.isEmpty()) {
                    sendRedMessage(Grug.runtimeErrorQueue.poll());
                }
            }

            synchronized (Grug.printQueue) {
                while (!Grug.printQueue.isEmpty()) {
                    sendMessage(Grug.printQueue.poll(), "");
                }
            }
        }
    }

    /**
     * Resizes the game window to the given size.
     *
     * <p>{@code Display.setDisplayMode()} alone isn't enough: it's a no-op while the Display is
     * parented to the Canvas, and the Canvas's size is owned by the layout of whatever contains it.
     * The top-level Window has to be resized, and the layout then sizes everything below it. The
     * Canvas is deliberately not assumed to be a direct child of a Frame; the launcher may wrap it.
     */
    private void applyDisplayMode(int width, int height) {
        try {
            Display.setDisplayMode(new DisplayMode(width, height));

            if (minecraft.canvas != null) {
                minecraft.canvas.setPreferredSize(new Dimension(width, height));
                minecraft.canvas.setSize(width, height);

                Window window = enclosingWindow(minecraft.canvas);
                if (window != null) {
                    Insets insets = window.getInsets();
                    window.setSize(
                            width + insets.left + insets.right,
                            height + insets.top + insets.bottom);
                    window.validate();
                }
            }

            access.grug$resize(width, height);
        } catch (Exception e) {
            // Resolution changes are finicky across platforms, so report rather than crash the
            // client.
            GrugModLoader.LOGGER.error("Failed to switch the window to " + width + "x" + height, e);
            sendRedMessage("Failed to switch the window to " + width + "x" + height + ".");
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

    /** Last cursor position printed to chat, or Integer.MIN_VALUE when nothing has been printed. */
    private int lastCursorX = Integer.MIN_VALUE;

    private int lastCursorY = Integer.MIN_VALUE;

    /**
     * Reads the mouse position in the same top-left-origin convention Screenshot.equals() takes, so
     * a coordinate read off chat can be pasted straight into a crop rectangle. Called every tick
     * while M holds the window at the test resolution, but only prints when the position changed,
     * so a stationary mouse doesn't fill chat.
     *
     * <p>This shares its flip with captureRectangle() in OrnitheAdapter: LWJGL's Mouse counts Y
     * from the bottom of the window, like GL does, whereas the crop rectangle counts from the top.
     */
    @GrugGenerated("dev-only: cursor-coordinate readout")
    private void updateCursorReadout() {
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
        if (minecraft.width != GrugScreenshots.WIDTH
                || minecraft.height != GrugScreenshots.HEIGHT
                || Mouse.isGrabbed()) {
            lastCursorX = Integer.MIN_VALUE;
            lastCursorY = Integer.MIN_VALUE;
            return;
        }

        int screenX = Mouse.getX();
        int screenY = minecraft.height - Mouse.getY();

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
            GrugModLoader.LOGGER.info(prefix + line);

            int maxLength = 50;
            while (line.length() > maxLength) {
                int splitIndex = line.lastIndexOf(' ', maxLength);
                if (splitIndex == -1) {
                    splitIndex = maxLength;
                }
                access.grug$sendChat(prefix + line.substring(0, splitIndex));
                line = line.substring(splitIndex).trim();
            }
            if (!line.isEmpty()) {
                access.grug$sendChat(prefix + line);
            }
        }
    }
}
