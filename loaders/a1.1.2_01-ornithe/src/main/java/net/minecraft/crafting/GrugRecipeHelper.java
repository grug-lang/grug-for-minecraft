package net.minecraft.crafting;

import net.minecraft.item.ItemStack;

public class GrugRecipeHelper {
    public static void addShaped(ItemStack output, Object... inputs) {
        CraftingManager.getInstance().registerShaped(output, inputs);
    }
}
