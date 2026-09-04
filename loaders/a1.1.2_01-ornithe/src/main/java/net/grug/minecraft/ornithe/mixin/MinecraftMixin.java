package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.gui.GameGui;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Canvas;
import java.awt.Container;
import java.awt.Frame;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow
    public ClientPlayerEntity player;

    @Shadow
    public GameGui gui;

    @Shadow
    public Canvas canvas;

    @Inject(method = "init", at = @At("TAIL"))
    private void grug$onInit(CallbackInfo ci) {
        String title = "Minecraft Alpha 1.1.2_01 - Ornithe with grug";

        // This handles the raw LWJGL window (used in production builds)
        Display.setTitle(title);

        // This traverses the AWT hierarchy to update the Applet Frame (used in dev
        // environments)
        if (this.canvas != null) {
            Container parent = this.canvas.getParent();
            while (parent != null && !(parent instanceof Frame)) {
                parent = parent.getParent();
            }
            if (parent instanceof Frame frame) {
                frame.setTitle(title);
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$onClientTick(CallbackInfo ci) {
        String[] updatedResources = Grug.update(this::sendRedMessage);
        for (String resource : updatedResources) {
            GrugModLoader.LOGGER.info("Reloading changed resource: {}", resource);
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
