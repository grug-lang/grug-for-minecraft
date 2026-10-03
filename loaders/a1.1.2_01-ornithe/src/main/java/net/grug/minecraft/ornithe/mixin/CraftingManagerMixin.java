package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugShapelessRecipes;
import net.minecraft.crafting.CraftingManager;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets grug's shapeless recipes craft on a game version that has none.
 *
 * <p>Alpha 1.1.2_01 registers shaped recipes only, so a mod that ships a shapeless one gets nothing
 * from the game. Every crafting result in this version comes from {@code
 * CraftingManager.getResult}, client and server alike, so asking the grug matcher here and
 * returning its stack when one fits gives those recipes the same treatment the game's own recipes
 * get.
 *
 * <p>The stack is copied because the caller consumes it: the game hands out a fresh stack from
 * {@code construct}, and a grug recipe's own stack has to survive being taken again next tick.
 */
@Mixin(CraftingManager.class)
public class CraftingManagerMixin {

    @Inject(method = "getResult", at = @At("HEAD"), cancellable = true)
    private void grug$shapelessRecipes(int[] grid, CallbackInfoReturnable<ItemStack> cir) {
        Object matched = GrugShapelessRecipes.match(grid);
        if (matched instanceof ItemStack result) {
            cir.setReturnValue(result.copy());
        }
    }
}
