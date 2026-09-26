package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.grug.minecraft.ornithe.OrnitheAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.options.KeyBinding;
import net.ornithemc.osl.keybinds.api.KeybindEvents;
import net.ornithemc.osl.keybinds.api.KeybindRegistry;
import org.lwjgl.LWJGLException;
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
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Window;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    public ClientPlayerEntity player;

    @Shadow
    public GameGui gui;

    @Shadow
    public abstract void shutdown();

    // Minecraft.resize() is private, so it can't be called directly. Java forbids "private
    // abstract", and a shadow only has to be at least as visible as its target, so this is
    // declared protected.
    @Shadow
    protected abstract void resize(int width, int height);

    @Unique
    private boolean grug$titleSet = false;

    @Unique
    private static KeyBinding grug$runTestsKey;

    @Unique
    private boolean grug$testsKeyPressed = false;

    @Unique
    private static KeyBinding grug$forceResolutionKey;

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

    /** True only while F7 (not F6) is the reason the window is forced, so only F7 restores it. */
    @Unique
    private boolean grug$resolutionForcedByTestRun = false;

    /** Whether GL_DITHER was on before a test run turned it off, so it can be put back. */
    @Unique
    private boolean grug$ditherWasEnabled = false;

    static {
        KeybindEvents.REGISTER_KEYBINDS.register(() -> {
            grug$runTestsKey = KeybindRegistry.register("key.grug.run_tests", Keyboard.KEY_F7, "Grug");
            grug$forceResolutionKey = KeybindRegistry.register("key.grug.force_test_resolution", Keyboard.KEY_F6, "Grug");
        });
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$onClientTick(CallbackInfo ci) {
        if (!this.grug$titleSet) {
            String title = "Minecraft Alpha 1.1.2_01 - Ornithe with grug";
            Display.setTitle(title);
            for (Frame frame : Frame.getFrames()) {
                frame.setTitle(title);
            }
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
                // The hotkey path leaves the game running so another F7 press can start a fresh run.
                if (fromCI) {
                    this.shutdown();
                }
            }
        }

        // Test runner hotkey logic
        if (grug$runTestsKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(grug$runTestsKey.keyCode);
            if (isKeyDown && !this.grug$testsKeyPressed) {
                this.grug$testsKeyPressed = true;
                this.startTestRunner(false);
            } else if (!isKeyDown) {
                this.grug$testsKeyPressed = false;
            }
        }

        // F6: force the window to the resolution screenshot tests are captured at
        if (grug$forceResolutionKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(grug$forceResolutionKey.keyCode);
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

        // While F6 is holding the window at the test resolution, keep the cursor's screenshot-crop
        // coordinates in chat. A test run is excluded because it forces the same resolution for its
        // own reasons and nobody is aiming a cursor at it.
        if (this.grug$savedDisplayMode != null && this.grug$testRunner == null) {
            this.updateCursorPositionReadout();
        } else {
            // Forget the last position while inactive, so the readout reappears the moment F6 is
            // switched back on even if the cursor hasn't moved since it was last on.
            this.grug$lastCursorX = Integer.MIN_VALUE;
            this.grug$lastCursorY = Integer.MIN_VALUE;
        }

        String[] updatedResources = Grug.update(this::sendRedMessage);
        boolean reloadClientResources = false;

        for (String resource : updatedResources) {
            GrugModLoader.LOGGER.info("Reloading changed resource: {}", resource);
            reloadClientResources = true;
        }

        if (reloadClientResources) {
            Minecraft mc = (Minecraft) (Object) this;
            mc.textureManager.reload();
        }

        if (this.player != null) {
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

    @Unique
    private void startTestRunner(boolean fromCI) {
        // Pressing the hotkey again while a run is still going shouldn't restart it.
        if (this.grug$testRunner != null) {
            return;
        }

        // Screenshot tests only compare equal at the resolution their reference was captured at,
        // so a run always happens at 1280x720 and the previous resolution comes back afterwards.
        // If F6 already forced that size, leave its state alone: this run didn't set it up, so it
        // shouldn't undo it, and a second F6 press remains the way back.
        if (this.grug$savedDisplayMode == null) {
            this.grug$savedDisplayMode = currentWindowDisplayMode();
            this.applyTestDisplayMode();
            this.grug$resolutionForcedByTestRun = true;
        }

        // OpenGL dithers by default, which puts +/-1 noise on roughly 30 pixels of a GUI-sized crop
        // and moves it around between runs. That's the whole difference between a pixel-exact
        // comparison and one that can never pass, so turn it off for the duration of the run.
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
        this.applyDisplayMode(new DisplayMode(
                OrnitheAdapter.TEST_SCREENSHOT_WIDTH, OrnitheAdapter.TEST_SCREENSHOT_HEIGHT));
    }

    /**
     * The size the game window is actually at.
     *
     * <p>Deliberately not {@code Display.getDisplayMode()}. Alpha parents the LWJGL Display to an
     * AWT Canvas, and LWJGL documents that in that mode the Display "inherits the size of the
     * parent, disregarding the currently set display mode": {@code getDisplayMode()} keeps reporting
     * the mode the Display was created with (the desktop resolution), and {@code setDisplayMode()}
     * doesn't resize anything. So saving {@code getDisplayMode()} and later restoring it resized the
     * window to the desktop resolution instead of the size the player had — which is why F7 left the
     * window small and zoomed. {@code Display.getWidth()/getHeight()} do report the canvas size here.
     */
    @Unique
    private static DisplayMode currentWindowDisplayMode() {
        return new DisplayMode(Display.getWidth(), Display.getHeight());
    }

    /**
     * Resizes the game window to the given mode.
     *
     * <p>{@code Display.setDisplayMode()} alone isn't enough: it's a no-op while the Display is
     * parented to Alpha's Canvas. The Canvas's size is in turn owned by the layout of whatever
     * contains it, so it isn't enough to size the Canvas directly either — that's what originally
     * left the game rendering 1280x720 clipped inside an 854x480 window. The top-level Window has to
     * be resized, and the layout then sizes everything below it.
     *
     * <p>The Canvas is deliberately not assumed to be a direct child of a Frame. Fabric launches
     * Alpha as an applet, so it actually sits in MinecraftApplet, inside AppletLauncher, inside
     * AppletFrame; a {@code getParent() instanceof Frame} check never matched and the Window was
     * never resized. The window's insets are added so its client area is exactly the requested mode
     * even when it has decorations.
     *
     * <p>{@code resize()} still has to be called so Minecraft's own width/height, GUI scale and
     * viewport follow immediately rather than one frame later. At the end of every frame Minecraft
     * re-reads {@code canvas.getWidth()/getHeight()} and, if either differs from its own
     * width/height, resets them to the canvas's size and calls {@code resize()} again — which is why
     * the Canvas and Minecraft have to agree.
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
        } catch (LWJGLException e) {
            // Resolution changes are finicky across platforms, so report rather than crash the client.
            GrugModLoader.LOGGER.error("Failed to switch the window to "
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
     * into a crop rectangle. Called every tick while F6 holds the window at the test resolution, but
     * only prints when the position changed, so a stationary mouse doesn't fill chat.
     *
     * <p>This shares its flip with captureRectangle() in OrnitheAdapter, which reads the same frame
     * back: LWJGL's Mouse counts Y from the bottom of the window, like GL does, whereas the crop
     * rectangle counts from the top.
     */
    @Unique
    private void updateCursorPositionReadout() {
        Minecraft mc = (Minecraft) (Object) this;

        // Only meaningful at the test resolution, with a free cursor. While the mouse is grabbed for
        // camera control Mouse.getX/Y accumulate movement deltas rather than pointing at a pixel, so
        // printing them would just fill chat with drift. A screen being open is what ungrabs it.
        if (mc.width != OrnitheAdapter.TEST_SCREENSHOT_WIDTH
                || mc.height != OrnitheAdapter.TEST_SCREENSHOT_HEIGHT
                || Mouse.isGrabbed()) {
            // Forget the last position too, so the readout comes back as soon as it's meaningful
            // again even if the cursor hasn't moved since it was last printed.
            this.grug$lastCursorX = Integer.MIN_VALUE;
            this.grug$lastCursorY = Integer.MIN_VALUE;
            return;
        }

        int screenX = Mouse.getX();
        int screenY = mc.height - Mouse.getY();

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
            GrugModLoader.LOGGER.info(prefix + line);

            int maxLength = 50;
            while (line.length() > maxLength) {
                int splitIndex = line.lastIndexOf(' ', maxLength);
                if (splitIndex == -1) {
                    splitIndex = maxLength;
                }
                if (this.gui != null) {
                    this.gui.addChatMessage(prefix + line.substring(0, splitIndex));
                }
                line = line.substring(splitIndex).trim();
            }
            if (!line.isEmpty() && this.gui != null) {
                this.gui.addChatMessage(prefix + line);
            }
        }
    }
}
