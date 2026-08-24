package com.example.examplemod.examplemod.mixin;

import com.example.examplemod.examplemod.grug.GameFunctions;
import com.example.examplemod.examplemod.grug.Grug;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ClientPlayerEntity;
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
        // Handle compilation / hot-reload errors
        Grug.update(this::sendRedMessage);

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
            while (line.length() > 50) {
                int splitIndex = line.lastIndexOf(' ', 50);
                if (splitIndex == -1) {
                    splitIndex = 50; // Force split mid-word if there are no spaces (like long file paths)
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
