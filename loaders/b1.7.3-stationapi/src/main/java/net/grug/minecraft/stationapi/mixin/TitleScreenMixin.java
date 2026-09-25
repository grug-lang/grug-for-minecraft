package net.grug.minecraft.stationapi.mixin;

import net.minecraft.client.SingleplayerInteractionManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {

    @Unique
    private boolean grug$autoLoaded = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$autoLoadWorld(CallbackInfo ci) {
        if (!this.grug$autoLoaded && "true".equals(System.getenv("GRUG_CI"))) {
            this.grug$autoLoaded = true;
            System.out.println("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");
            this.minecraft.interactionManager = new SingleplayerInteractionManager(this.minecraft);
            this.minecraft.startGame("World1", "World1", 0L);
            this.minecraft.setScreen(null);
        }
    }
}
