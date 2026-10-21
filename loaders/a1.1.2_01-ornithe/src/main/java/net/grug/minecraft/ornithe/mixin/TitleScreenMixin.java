package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.client.GrugModsButton;
import net.minecraft.client.ClientPlayerInteractionManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    @Inject(method = "init", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void grug$autoLoadWorld(CallbackInfo ci) {
        this.buttons.add(new GrugModsButton(this));
        if ("true".equals(System.getenv("GRUG_CI"))) {
            System.out.println("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");
            this.minecraft.interactionManager = new ClientPlayerInteractionManager(this.minecraft);
            this.minecraft.startGame("World1");
            this.minecraft.openScreen(null);
        }
    }
}
