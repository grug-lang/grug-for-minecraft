package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.client.GrugOverlayHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets an overlay take a mouse wheel movement before the screen's mouse handler runs.
 *
 * <p>Beta's screens never read the wheel: {@code onMouseEvent} runs once per mouse event and only
 * looks at the buttons, so its head is the one place a wheel movement can be taken. It is declared
 * on {@code Screen}, which is why this is not part of {@code HandledScreenMixin}: an injection can
 * only target a method the mixin's own class declares. The overlay's logic lives in {@link
 * GrugOverlayHost}, because a mixin class is not measured by the coverage gate.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Shadow protected Minecraft minecraft;

    @Inject(method = "onMouseEvent", at = @At("HEAD"), cancellable = true)
    private void grug$takeWheel(CallbackInfo ci) {
        if (GrugOverlayHost.wheel(
                (Screen) (Object) this, minecraft.displayWidth, minecraft.displayHeight)) {
            ci.cancel();
        }
    }
}
