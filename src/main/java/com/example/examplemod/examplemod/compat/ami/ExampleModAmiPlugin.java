package com.example.examplemod.examplemod.compat.ami;

import com.example.examplemod.examplemod.block.GrugBlock;
import com.example.examplemod.examplemod.events.init.InitListener;
import com.example.examplemod.examplemod.grug.Grug;
import net.glasslauncher.mods.alwaysmoreitems.api.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.modificationstation.stationapi.api.util.Identifier;

public class ExampleModAmiPlugin implements ModPluginProvider {
    public static AMIHelpers amiHelpers;

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
        InitListener.LOGGER.info("AMI onAMIHelpersAvailable called!"); // TODO: Remove!
        ExampleModAmiPlugin.amiHelpers = amiHelpers;
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
        InitListener.LOGGER.info("AMI in updateBlacklist"); // TODO: Remove!

        // Expose helpers globally
        ExampleModAmiPlugin.amiHelpers = amiHelpers;

        // // Hide all dynamically pre-allocated blocks using the -1 wildcard
        // for (GrugBlock block : Grug.availableDynamicBlocks) {
        // InitListener.LOGGER.info("AMI in updateBlacklist with block '{}'",
        // block.identifier); // TODO: Remove!

        // amiHelpers.getItemBlacklist().addItemToBlacklist(new ItemStack(block, 1,
        // -1));
        // }
    }
}
