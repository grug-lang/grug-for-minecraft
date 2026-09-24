package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow
    public ClientPlayerEntity player;

    @Unique
    private boolean grug$titleSet = false;

    @Unique
    private static KeyBinding grug$runTestsKey;

    @Unique
    private boolean grug$testsKeyPressed = false;

    static {
        KeybindEvents.REGISTER_KEYBINDS.register(() -> {
            grug$runTestsKey = KeybindRegistry.register("key.grug.run_tests", Keyboard.KEY_F7, "Grug");
        });
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$onClientTick(CallbackInfo ci) {
        if (!this.grug$titleSet) {
            String title = "Minecraft Beta 1.7.3 - Ornithe with grug";
            Display.setTitle(title);

            for (Frame frame : Frame.getFrames()) {
                frame.setTitle(title);
            }

            GrugModLoader.LOGGER.info("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");

            this.grug$titleSet = true;
        }

        // Test runner hotkey logic
        if (grug$runTestsKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(grug$runTestsKey.keyCode);
            if (isKeyDown && !this.grug$testsKeyPressed) {
                this.grug$testsKeyPressed = true;
                grug$runAllTests();
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
    private void grug$runAllTests() {
        List<Map.Entry<String, Long>> tests = new ArrayList<>();
        for (Map.Entry<String, Long> entry : Grug.fileIds.entrySet()) {
            if (entry.getKey().endsWith("-Test.grug")) {
                tests.add(entry);
            }
        }

        String startMsg = "Running " + tests.size() + " " + (tests.size() == 1 ? "test" : "tests") + "...";
        GrugModLoader.LOGGER.info(startMsg);
        if (this.player != null) {
            sendMessage(startMsg, "");
        }

        for (Map.Entry<String, Long> entry : tests) {
            String path = entry.getKey();
            long fileId = entry.getValue();
            long entityHandle = 0;

            try {
                entityHandle = Grug.createEntity(fileId);
                if (entityHandle != 0) {
                    long fnId = Grug.getExportFnId("Test", "run");
                    if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                        Grug.callExportFn(entityHandle, fnId);

                        GrugModLoader.LOGGER.info("PASS " + path);
                        if (this.player != null) {
                            sendMessage("PASS " + path, "\u00A7a"); // \u00A7a is light green
                        }
                    } else {
                        throw new RuntimeException("Test entity missing 'run' export function.");
                    }
                }
            } catch (Exception e) {
                Grug.printQueue.clear();

                String msg = e.getMessage();
                if (msg != null && msg.startsWith("Broken grug invariant: ")) {
                    msg = msg.substring(23);
                } else if (msg == null) {
                    msg = e.toString();
                }

                GrugModLoader.LOGGER.error("FAIL " + path);
                GrugModLoader.LOGGER.error(msg);

                if (this.player != null) {
                    sendRedMessage("FAIL " + path);
                    sendRedMessage(msg);
                }

                break;
            } finally {
                if (entityHandle != 0) {
                    Grug.destroyEntity(entityHandle);
                }
            }
        }
    }

    private void sendRedMessage(String text) {
        sendMessage(text, "\u00A7c");
    }

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
                this.player.addMessage(prefix + line.substring(0, splitIndex));
                line = line.substring(splitIndex).trim();
            }
            if (!line.isEmpty()) {
                this.player.addMessage(prefix + line);
            }
        }
    }
}
