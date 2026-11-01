package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.client.GrugOverlayHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clears the overlay's drawn mark when a screen opens or closes, and lets an overlay take a mouse
 * wheel movement before the screen's mouse handler runs.
 *
 * <p>Beta's {@code InventoryMenuScreen} overrides {@code init} and calls this class's
 * implementation, while Alpha's inherits it, so an injection on {@code Screen} is the one hook both
 * versions have. Clearing on every screen, not only the container screens the overlay draws on,
 * keeps the mark from outliving the screen it was noted on.
 *
 * <p>The wheel is hooked here too, because neither version's screens read it: {@code handleMouse}
 * runs once per mouse event and only looks at the buttons, so its head is the one place a wheel
 * movement can be taken, and it is declared on {@code Screen} for both versions.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Shadow protected Minecraft minecraft;

    @Inject(method = "init", at = @At("TAIL"))
    private void grug$screenOpened(CallbackInfo ci) {
        GrugOverlayHost.screenOpened((Screen) (Object) this);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void grug$screenClosed(CallbackInfo ci) {
        GrugOverlayHost.screenClosed();
    }

    @Inject(method = "handleMouse", at = @At("HEAD"), cancellable = true)
    private void grug$takeWheel(CallbackInfo ci) {
        if (GrugOverlayHost.wheel((Screen) (Object) this, minecraft.width, minecraft.height)) {
            ci.cancel();
        }
    }
}
