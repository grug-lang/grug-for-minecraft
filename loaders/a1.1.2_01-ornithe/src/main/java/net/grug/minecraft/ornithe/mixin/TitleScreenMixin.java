package net.grug.minecraft.ornithe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.client.gui.screen.TitleScreen;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void grug$onInit(CallbackInfo ci) {
        GrugModLoader.LOGGER.info("This line is printed by a grug mixin in Ornithe!");
    }
}
