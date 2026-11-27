package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.GrugClientHooks;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows a declared binding's label in the Controls screen.
 *
 * <p>{@code GameOptions.getKeybindName} translates the binding's name through the language table,
 * which answers the name itself when it has no translation, so a stable saved name would be shown
 * as it is (#1849). This answers the label the mod declared instead, and leaves every vanilla row
 * on the game's own translation.
 */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {

    @Shadow public KeyBinding[] keyBindings;

    @Inject(method = "getKeyBindingName", at = @At("HEAD"), cancellable = true)
    private void grug$showDeclaredLabel(int index, CallbackInfoReturnable<String> cir) {
        if (index < 0 || index >= this.keyBindings.length) return;
        String label = GrugClientHooks.declaredLabel(this.keyBindings[index].name);
        if (label != null) {
            cir.setReturnValue(label);
        }
    }
}
