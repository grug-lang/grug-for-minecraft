package com.example.examplemod.examplemod.mixin;

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
        Grug.update(errorMessage -> {
            if (this.player != null) {
                // Minecraft chat breaks if given \n characters, so we must split them!
                String[] lines = errorMessage.split("\n");
                for (String line : lines) {
                    // \u00A7c is the internal Minecraft color code for red text
                    this.player.sendMessage("\u00A7c" + line);
                }
            }
        });
    }
}
