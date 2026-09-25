package net.grug.minecraft.ornithe.mixin;

import net.minecraft.client.SurvivalInteractionManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    @Inject(method = "init", at = @At("RETURN"))
    private void grug$autoLoadWorld(CallbackInfo ci) {
        if ("true".equals(System.getenv("GRUG_CI"))) {
            System.out.println("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");
            this.minecraft.interactionManager = new SurvivalInteractionManager(this.minecraft);
            this.minecraft.startGame("World1", "World1", 0L);
            this.minecraft.openScreen(null);
        }
    }
}
