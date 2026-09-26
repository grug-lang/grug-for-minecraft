package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.options.KeyBinding;
import net.ornithemc.osl.keybinds.api.KeybindEvents;
import net.ornithemc.osl.keybinds.api.KeybindRegistry;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
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

    @Unique
    private boolean grug$titleSet = false;

    @Unique
    private static KeyBinding grug$runTestsKey;

    @Unique
    private boolean grug$testsKeyPressed = false;

    @Unique
    private boolean grug$ciTestsRan = false;

    @Unique
    private GrugTestRunner grug$testRunner = null;

    @Unique
    private boolean grug$testRunnerFromCI = false;

    static {
        KeybindEvents.REGISTER_KEYBINDS.register(() -> {
            grug$runTestsKey = KeybindRegistry.register("key.grug.run_tests", Keyboard.KEY_F7, "Grug");
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
        this.grug$testRunner = new GrugTestRunner();
        this.grug$testRunnerFromCI = fromCI;
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
