package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.stationapi.events.init.ClientInitListener;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.modificationstation.stationapi.api.client.resource.ReloadableAssetsManager;
import net.modificationstation.stationapi.api.tick.TickScheduler;
import net.modificationstation.stationapi.api.util.Util;
import net.modificationstation.stationapi.impl.client.resource.AssetsReloaderImpl;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    private boolean grug$testsKeyPressed = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTick(CallbackInfo ci) {
        if (!this.grug$titleSet) {
            Display.setTitle("Minecraft Beta 1.7.3 - StationAPI with grug");
            InitListener.LOGGER.info("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");
            this.grug$titleSet = true;
        }

        // Test runner hotkey logic
        if (ClientInitListener.runTestsKey != null) {
            boolean isKeyDown = Keyboard.isKeyDown(ClientInitListener.runTestsKey.code);
            if (isKeyDown && !this.grug$testsKeyPressed) {
                this.grug$testsKeyPressed = true;
                grug$runAllTests();
            } else if (!isKeyDown) {
                this.grug$testsKeyPressed = false;
            }
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
    private void grug$runAllTests() {
        List<Map.Entry<String, Long>> tests = new ArrayList<>();
        for (Map.Entry<String, Long> entry : Grug.fileIds.entrySet()) {
            if (entry.getKey().endsWith("-Test.grug")) {
                tests.add(entry);
            }
        }

        String startMsg = "Running " + tests.size() + " " + (tests.size() == 1 ? "test" : "tests") + "...";
        InitListener.LOGGER.info(startMsg);
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

                        InitListener.LOGGER.info("PASS " + path);
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

                InitListener.LOGGER.error("FAIL " + path);
                InitListener.LOGGER.error(msg);

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
            // Print the full, unsplit line to the console
            InitListener.LOGGER.info(prefix + line);

            // Break long lines into chunks of ~50 characters
            // so the color code is preserved for the player
            int maxLength = 50;
            while (line.length() > maxLength) {
                int splitIndex = line.lastIndexOf(' ', maxLength);
                if (splitIndex == -1) {
                    splitIndex = maxLength; // Force split mid-word if there are no spaces (like long file paths)
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
