package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.client.GrugOverlayHost;
import net.minecraft.client.gui.screen.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clears the overlay's drawn mark when a screen opens or closes.
 *
 * <p>Beta's {@code InventoryMenuScreen} overrides {@code init} and calls this class's
 * implementation, while Alpha's inherits it, so an injection on {@code Screen} is the one hook both
 * versions have. Clearing on every screen, not only the container screens the overlay draws on,
 * keeps the mark from outliving the screen it was noted on.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void grug$screenOpened(CallbackInfo ci) {
        GrugOverlayHost.screenOpened((Screen) (Object) this);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void grug$screenClosed(CallbackInfo ci) {
        GrugOverlayHost.screenClosed();
    }
}
