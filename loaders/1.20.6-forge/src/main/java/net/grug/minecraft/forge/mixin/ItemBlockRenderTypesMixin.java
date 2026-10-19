package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.block.GrugBlock;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws a block that declared set_flat_lighting with a block sheet when its item is rendered, so
 * its item form is unlit.
 *
 * <p>A block item is drawn with an entity sheet, {@code Sheets.solidItemSheet} or {@code
 * Sheets.cutoutBlockSheet}, and the entity vertex shader multiplies the texture by {@code
 * minecraft_mix_light}, a per-face directional shade. BuildCraft 2.2.14's engine renderer draws
 * with GL lighting off, so every face is at full texture brightness; the block sheet's shader
 * lights a face from the lightmap alone, which the item renderer puts at full brightness. The item
 * model then reads the same as its reference.
 *
 * <p>The change is scoped to the block, so every other item keeps the entity sheet it had, and it
 * reaches the item in the GUI, in the hand and on the ground, which are all drawn through this.
 */
@Mixin(ItemBlockRenderTypes.class)
public class ItemBlockRenderTypesMixin {

    /** The unlit block sheet of {@code grug}, in place of the entity sheet. */
    @Inject(
            method =
                    "getRenderType(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/client/renderer/RenderType;",
            at = @At("HEAD"),
            cancellable = true)
    private static void grug$flatItem(
            ItemStack stack, boolean translucent, CallbackInfoReturnable<RenderType> cir) {
        Item item = stack.getItem();
        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block instanceof GrugBlock grugBlock && grugBlock.drawsFlatLighting()) {
                cir.setReturnValue(RenderType.cutout());
            }
        }
    }
}
