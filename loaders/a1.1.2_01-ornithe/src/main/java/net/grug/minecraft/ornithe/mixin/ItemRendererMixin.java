package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.client.GrugModelRenderer;
import net.minecraft.client.render.entity.ItemRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the stretch in which Alpha draws an item into a GUI slot, which is the only place it shows
 * a block item's far side. See {@link GrugModelRenderer#drawItemForm(net.minecraft.block.Block,
 * boolean)}.
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    @Inject(method = "renderGuiItem", at = @At("HEAD"))
    private void grug$enterGui(CallbackInfo ci) {
        GrugModelRenderer.drawingGuiItem = true;
    }

    @Inject(method = "renderGuiItem", at = @At("RETURN"))
    private void grug$leaveGui(CallbackInfo ci) {
        GrugModelRenderer.drawingGuiItem = false;
    }
}
