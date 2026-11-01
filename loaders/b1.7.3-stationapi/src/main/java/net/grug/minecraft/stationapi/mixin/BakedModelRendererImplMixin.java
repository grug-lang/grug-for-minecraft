package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.client.GrugModelRenderer;
import net.minecraft.item.ItemStack;
import net.modificationstation.stationapi.api.client.render.model.BakedModel;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns OpenGL lighting off around the quads of a grug block's item form, wherever this loader's
 * item renderer draws them into the world: in the hand and on the ground.
 *
 * <p>2.2.14's {@code RenderEngine.render}, which every one of its item render types goes through,
 * turns lighting off, so the engine's item is unshaded in all of them. This loader's baked item
 * renderer takes the game's per-face shade whenever a model is side lit, which the engine's is.
 * {@link GrugModelRenderer#drawsUnlitItem(ItemStack)} names the items this applies to, and the GUI
 * slot is drawn from the model directly instead.
 *
 * <p>The class is StationAPI's own renderer, which this loader has no compile-time dependency on,
 * so it is named by string. Both the hand and the ground reach it, through {@code
 * ArsenicOverlayRenderer.renderItem3D} and {@code ArsenicItemRenderer.render}, so one hook covers
 * both.
 */
@Mixin(
        targets =
                "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                        + ".BakedModelRendererImpl")
public class BakedModelRendererImplMixin {

    @Inject(
            method =
                    "renderBakedItemModel(Lnet/modificationstation/stationapi/api/client/render/"
                            + "model/BakedModel;Lnet/minecraft/item/ItemStack;F)V",
            at = @At("HEAD"))
    private void grug$unlitHead(
            BakedModel model, ItemStack stack, float brightness, CallbackInfo ci) {
        if (!GrugModelRenderer.drawsUnlitItem(stack)) return;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    @Inject(
            method =
                    "renderBakedItemModel(Lnet/modificationstation/stationapi/api/client/render/"
                            + "model/BakedModel;Lnet/minecraft/item/ItemStack;F)V",
            at = @At("RETURN"))
    private void grug$unlitReturn(
            BakedModel model, ItemStack stack, float brightness, CallbackInfo ci) {
        if (!GrugModelRenderer.drawsUnlitItem(stack)) return;

        GL11.glPopAttrib();
    }
}
