package net.grug.minecraft.ornithe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.grug.minecraft.ornithe.GrugModLoader;
import net.grug.minecraft.ornithe.block.GrugBlocks;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.crafting.GrugRecipeHelper;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    private static boolean recipesAdded = false;

    @Inject(method = "init", at = @At("TAIL"))
    private void grug$onInit(CallbackInfo ci) {
        GrugModLoader.LOGGER.info("This line is printed by a grug mixin in Ornithe!");

        if (!recipesAdded) {
            for (int id = GrugBlocks.START_BLOCK_ID; id < GrugBlocks.START_BLOCK_ID
                    + GrugModLoader.blockFiles.size(); id++) {
                if (Block.BY_ID[id] != null) {
                    GrugRecipeHelper.addShaped(
                            new ItemStack(Block.BY_ID[id], 64),
                            "D",
                            'D', Block.DIRT);
                }
            }
            recipesAdded = true;
            GrugModLoader.LOGGER.info("Temporary testing recipes added for dynamic Grug blocks!");
        }
    }
}
