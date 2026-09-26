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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Frame;

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

    @Unique
    private static KeyBinding grug$printCursorKey;

    @Unique
    private boolean grug$printCursorKeyPressed = false;

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

    static {
        KeybindEvents.REGISTER_KEYBINDS.register(() -> {
            grug$runTestsKey = KeybindRegistry.register("key.grug.run_tests", Keyboard.KEY_F7, "Grug");
            grug$forceResolutionKey = KeybindRegistry.register("key.grug.force_test_resolution", Keyboard.KEY_F6, "Grug");
            grug$printCursorKey = KeybindRegistry.register("key.grug.print_cursor_pos", Keyboard.KEY_F5, "Grug");
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
                this.restoreResolutionAfterTestRun();
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
                    this.grug$savedDisplayMode = Display.getDisplayMode();
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

        // F5: print where the cursor is, in screenshot-crop coordinates
        if (grug$printCursorKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(grug$printCursorKey.keyCode);
            if (isKeyDown && !this.grug$printCursorKeyPressed) {
                this.grug$printCursorKeyPressed = true;
                this.printCursorPosition();
            } else if (!isKeyDown) {
                this.grug$printCursorKeyPressed = false;
            }
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
            this.grug$savedDisplayMode = Display.getDisplayMode();
            this.applyTestDisplayMode();
            this.grug$resolutionForcedByTestRun = true;
        }

        this.grug$testRunner = new GrugTestRunner();
        this.grug$testRunnerFromCI = fromCI;
    }

    /** Puts the window back the way this run found it, but only if this run changed it. */
    @Unique
    private void restoreResolutionAfterTestRun() {
        if (!this.grug$resolutionForcedByTestRun) {
            return;
        }
        DisplayMode previous = this.grug$savedDisplayMode;
        this.grug$savedDisplayMode = null;
        this.grug$resolutionForcedByTestRun = false;
        this.applyDisplayMode(previous);
    }

    /** Switches the window to the resolution screenshot tests are captured at. */
    @Unique
    private void applyTestDisplayMode() {
        this.applyDisplayMode(new DisplayMode(
                OrnitheAdapter.TEST_SCREENSHOT_WIDTH, OrnitheAdapter.TEST_SCREENSHOT_HEIGHT));
    }

    /**
     * LWJGL 2's DisplayMode only controls the window itself, so Minecraft also has to be told,
     * or its GUI scale and viewport stay sized for the old mode.
     */
    @Unique
    private void applyDisplayMode(DisplayMode mode) {
        try {
            Display.setDisplayMode(mode);
            this.resize(mode.getWidth(), mode.getHeight());
        } catch (LWJGLException e) {
            // Resolution changes are finicky across platforms, so report rather than crash the client.
            GrugModLoader.LOGGER.error("Failed to switch the window to "
                    + mode.getWidth() + "x" + mode.getHeight(), e);
            sendRedMessage("Failed to switch the window to " + mode.getWidth() + "x" + mode.getHeight() + ".");
        }
    }

    /**
     * Reads the mouse position in the same top-left-origin convention
     * Test.assert_screenshot_equals() takes, so a coordinate read off chat can be pasted straight
     * into a crop rectangle.
     *
     * <p>This shares its flip with captureRectangle() in OrnitheAdapter, which reads the same frame
     * back: LWJGL's Mouse counts Y from the bottom of the window, like GL does, whereas the crop
     * rectangle counts from the top.
     */
    @Unique
    private void printCursorPosition() {
        Minecraft mc = (Minecraft) (Object) this;

        if (mc.width != OrnitheAdapter.TEST_SCREENSHOT_WIDTH
                || mc.height != OrnitheAdapter.TEST_SCREENSHOT_HEIGHT) {
            sendRedMessage("The window is " + mc.width + "x" + mc.height
                    + "; press F6 to resize to " + OrnitheAdapter.TEST_SCREENSHOT_WIDTH + "x"
                    + OrnitheAdapter.TEST_SCREENSHOT_HEIGHT + " first.");
            return;
        }

        int screenX = Mouse.getX();
        int screenY = mc.height - Mouse.getY();

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
