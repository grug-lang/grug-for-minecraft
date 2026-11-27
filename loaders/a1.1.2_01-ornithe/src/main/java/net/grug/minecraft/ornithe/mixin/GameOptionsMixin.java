package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.GrugClientHooks;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;

import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows a declared binding's label in the Controls screen.
 *
 * <p>Alpha has no translation layer: {@code GameOptions.getBoundKeyName} builds the row's text from
 * the binding's name, which is the stable saved name that keeps a rebind with its mod and key
 * (#1849). This answers the label the mod declared instead, with the key beside it the way the
 * vanilla rows read, so only the saved name changes.
 */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {

    @Shadow public KeyBinding[] keyBindings;

    @Inject(method = "getBoundKeyName", at = @At("HEAD"), cancellable = true)
    private void grug$showDeclaredLabel(int index, CallbackInfoReturnable<String> cir) {
        if (index < 0 || index >= this.keyBindings.length) return;
        KeyBinding binding = this.keyBindings[index];
        String label = GrugClientHooks.declaredLabel(binding.name);
        if (label != null) {
            cir.setReturnValue(label + ':' + Keyboard.getKeyName(binding.keyCode));
        }
    }
}
