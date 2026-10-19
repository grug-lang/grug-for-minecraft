package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.modificationstation.stationapi.api.client.render.model.BakedModel;
import net.modificationstation.stationapi.impl.client.arsenic.renderer.render.BakedModelRendererImpl;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a block that declared set_flat_lighting with GL lighting off when its item is drawn in the
 * GUI.
 *
 * <p>StationAPI bakes an item model and draws it with the game's fixed-function lighting, so every
 * face is shaded by its normal, and it only turns that lighting off for a model whose GUI light
 * says it is not side lit. BuildCraft 2.2.14's engine renderer disables GL lighting and draws every
 * face at full texture brightness, so the engine item would otherwise read darker than its
 * reference. This wraps the GUI draw itself, because the renderer batches its vertices and only
 * issues the draw after the item renderer has returned.
 *
 * <p>The block is found from the block item's own block id, because a grug block extends a
 * StationAPI template block, whose item is a template block item rather than the block item form
 * the vanilla block item interface describes.
 *
 * <p>The change is scoped to the stack's block, so every other item keeps the game's lighting.
 */
@Mixin(BakedModelRendererImpl.class)
public class BakedModelRendererImplMixin {

    @Inject(
            method =
                    "renderGuiItemModel(Lnet/minecraft/item/ItemStack;ILnet/modificationstation/stationapi/api/client/render/model/BakedModel;)V",
            at = @At("HEAD"))
    private void grug$flatItemHead(
            ItemStack stack, int x, int y, BakedModel model, CallbackInfo ci) {
        if (grug$isFlat(stack)) {
            GL11.glDisable(GL11.GL_LIGHTING);
        }
    }

    @Inject(
            method =
                    "renderGuiItemModel(Lnet/minecraft/item/ItemStack;ILnet/modificationstation/stationapi/api/client/render/model/BakedModel;)V",
            at = @At("RETURN"))
    private void grug$flatItemReturn(
            ItemStack stack, int x, int y, BakedModel model, CallbackInfo ci) {
        if (grug$isFlat(stack)) {
            GL11.glEnable(GL11.GL_LIGHTING);
        }
    }

    private static boolean grug$isFlat(ItemStack stack) {
        if (stack == null) return false;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        int id = ((BlockItemAccessor) blockItem).grug$getBlockId();
        if (id < 0 || id >= Block.BLOCKS.length) return false;
        Block block = Block.BLOCKS[id];
        return block instanceof GrugBlock grugBlock && grugBlock.drawsFlatLighting();
    }
}
