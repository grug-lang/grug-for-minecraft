package net.grug.minecraft.mixin;

import net.grug.minecraft.events.init.InitListener;
import net.grug.minecraft.grug.Grug;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.modificationstation.stationapi.api.client.resource.ReloadableAssetsManager;
import net.modificationstation.stationapi.api.tick.TickScheduler;
import net.modificationstation.stationapi.api.util.Util;
import net.modificationstation.stationapi.impl.client.resource.AssetsReloaderImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow
    public ClientPlayerEntity player;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTick(CallbackInfo ci) {
        // Handle compilation / hot-reload errors, and reload assets if a
        // resource referenced by a grug script (e.g. via set_texture) changed
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

    private void sendRedMessage(String text) {
        sendMessage(text, "\u00A7c");
    }

    private void sendMessage(String text, String prefix) {
        if (this.player == null || text == null)
            return;

        String[] lines = text.split("\n");
        for (String line : lines) {
            // Break long lines into chunks of ~50 characters so the color code is preserved
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
