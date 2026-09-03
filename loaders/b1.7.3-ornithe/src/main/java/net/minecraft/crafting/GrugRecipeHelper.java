package net.minecraft.crafting;

import net.minecraft.item.ItemStack;

public class GrugRecipeHelper {
    // Because this class is in net.minecraft.crafting, it is allowed to call
    // registerShapeless!
    public static void addShapeless(ItemStack output, Object... inputs) {
        CraftingManager.getInstance().registerShapeless(output, inputs);
    }
}
