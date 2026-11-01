package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.client.GrugModelRenderer;
import net.minecraft.block.Block;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.item.ItemStack;
import net.modificationstation.stationapi.api.item.BlockItemForm;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a grug block's item form into a GUI slot instead of letting StationAPI draw the item.
 *
 * <p>StationAPI draws a block item whose model its bake produced, shaded by fixed-function
 * lighting, which is what a side lit model asks for. A grug model that declares set_flat_lighting
 * is drawn by 2.2.14's item renderer with lighting off, so the slot shows it unshaded instead. See
 * {@link GrugModelRenderer#drawItemFormInGui(Block, int, int)} for why the model is not simply
 * marked front lit.
 *
 * <p>The priority puts this ahead of the injector StationAPI's item renderer adds to this method.
 * Mixin applies lower priorities first, and that injector cancels the method once it has drawn the
 * model itself, so without running first this one would never see a block item that has a model.
 */
@Mixin(value = ItemRenderer.class, priority = 500)
public class ItemRendererMixin {

    @Inject(
            method =
                    "renderGuiItem(Lnet/minecraft/client/font/TextRenderer;"
                            + "Lnet/minecraft/client/texture/TextureManager;"
                            + "Lnet/minecraft/item/ItemStack;II)V",
            at = @At("HEAD"),
            cancellable = true)
    private void grug$customGuiItem(
            TextRenderer textRenderer,
            TextureManager textureManager,
            ItemStack stack,
            int x,
            int y,
            CallbackInfo ci) {
        if (stack == null || !(stack.getItem() instanceof BlockItemForm)) return;

        Block block = ((BlockItemForm) stack.getItem()).getBlock();
        if (GrugModelRenderer.drawItemFormInGui(block, x, y)) {
            ci.cancel();
        }
    }
}
