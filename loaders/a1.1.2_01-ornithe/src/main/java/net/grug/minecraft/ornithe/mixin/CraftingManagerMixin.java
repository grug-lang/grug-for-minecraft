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
 * CraftingManager.getResult}, client and server alike, which is why asking the grug matcher here is
 * enough for those recipes to work.
 *
 * <p>It asks at the end rather than at the head, which is the order Beta 1.7.3 already gives these
 * recipes: the game's own list is tried first and a grug shapeless recipe answers only the grids
 * nothing else matched. Asking first would let a mod's shapeless recipe answer a grid the version's
 * own shaped recipe already matches, so the same recipe file would craft differently here than it
 * does on every loader that has shapeless recipes of its own.
 *
 * <p>The stack is copied because the caller consumes it: the game hands out a fresh stack from
 * {@code construct}, and a grug recipe's own stack has to survive being taken again next tick.
 */
@Mixin(CraftingManager.class)
public class CraftingManagerMixin {

    @Inject(method = "getResult", at = @At("TAIL"), cancellable = true)
    private void grug$shapelessRecipes(int[] grid, CallbackInfoReturnable<ItemStack> cir) {
        if (cir.getReturnValue() != null) return;
        Object matched = GrugShapelessRecipes.match(grid);
        if (matched instanceof ItemStack result) {
            cir.setReturnValue(result.copy());
        }
    }
}
