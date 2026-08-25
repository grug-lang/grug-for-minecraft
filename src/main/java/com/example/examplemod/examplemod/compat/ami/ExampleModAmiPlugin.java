package com.example.examplemod.examplemod.compat.ami;

import com.example.examplemod.examplemod.block.GrugBlock;
import com.example.examplemod.examplemod.events.init.InitListener;
import com.example.examplemod.examplemod.grug.Grug;
import net.glasslauncher.mods.alwaysmoreitems.api.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.modificationstation.stationapi.api.util.Identifier;

public class ExampleModAmiPlugin implements ModPluginProvider {
    @Override
    public String getName() {
        return "Example Mod";
    }

    @Override
    public Identifier getId() {
        return Identifier.of(InitListener.NAMESPACE, "examplemod");
    }

    @Override
    public void onAMIHelpersAvailable(AMIHelpers amiHelpers) {
    }

    @Override
    public void onItemRegistryAvailable(ItemRegistry itemRegistry) {
    }

    @Override
    public void register(ModRegistry registry) {
    }

    @Override
    public void onRecipeRegistryAvailable(RecipeRegistry recipeRegistry) {
    }

    @Override
    public SyncableRecipe deserializeRecipe(NbtCompound recipe) {
        return null;
    }

    @Override
    public void updateBlacklist(AMIHelpers amiHelpers) {
        // Hide all dynamically pre-allocated blocks that are currently unused
        for (GrugBlock block : Grug.availableDynamicBlocks) {
            amiHelpers.getItemBlacklist().addItemToBlacklist(new ItemStack(block));
        }
    }
}
