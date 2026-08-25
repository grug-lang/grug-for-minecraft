package com.example.examplemod.examplemod.mixin;

import com.example.examplemod.examplemod.events.init.InitListener;
import com.example.examplemod.examplemod.grug.GameFunctions;
import com.example.examplemod.examplemod.grug.Grug;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.modificationstation.stationapi.api.StationAPI;
import net.modificationstation.stationapi.api.client.event.resource.AssetsReloadEvent;
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
        }
        if (updatedResources.length > 0) {
            StationAPI.EVENT_BUS.post(AssetsReloadEvent.builder().build());
        }

        // Handle runtime errors triggered by grug-rs
        if (this.player != null) {
            synchronized (GameFunctions.runtimeErrorQueue) {
                while (!GameFunctions.runtimeErrorQueue.isEmpty()) {
                    sendRedMessage(GameFunctions.runtimeErrorQueue.poll());
                }
            }
        }
    }

    private void sendRedMessage(String text) {
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
                this.player.sendMessage("\u00A7c" + line.substring(0, splitIndex));
                line = line.substring(splitIndex).trim(); // Remove leading space for the next line
            }
            if (!line.isEmpty()) {
                this.player.sendMessage("\u00A7c" + line);
            }
        }
    }
}
