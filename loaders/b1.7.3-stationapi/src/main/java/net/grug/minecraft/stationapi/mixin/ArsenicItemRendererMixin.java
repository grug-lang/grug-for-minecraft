package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.client.GrugModelRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.modificationstation.stationapi.api.client.render.model.BakedModel;
import net.modificationstation.stationapi.api.client.texture.SpriteAtlasTexture;
import net.modificationstation.stationapi.impl.client.arsenic.renderer.render.ArsenicItemRenderer;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a grug block's item form on the ground with OpenGL lighting off, as 2.2.14 does.
 *
 * <p>2.2.14's {@code RenderEngine.render}, which every one of its item render types goes through,
 * turns lighting off, so the engine's item is unshaded wherever it is drawn. This loader's item
 * renderer accumulates the model's quads into the tessellator and flushes them after {@code
 * renderItem} returns, and fixed-function lighting is applied at that flush, so the disable has to
 * span it. {@link GrugModelRenderer#drawsUnlitItem(ItemStack)} names the items it applies to; here
 * it is evaluated once, when the draw starts, so the flush cannot disagree with it. The answer is
 * cleared when the whole call returns rather than after a flush, because a dropped stack flushes
 * once per item in it and every item is the same form.
 *
 * <p>A dropped item is the only thing this class draws, and {@code renderModel} is the path its
 * model takes; a vanilla model goes through {@code renderVanilla}, which cannot be a grug item.
 */
@Mixin(ArsenicItemRenderer.class)
public class ArsenicItemRendererMixin {

    /**
     * Whether the draw that is running now is one the reference lights off.
     *
     * <p>It holds for the whole call, not for one flush: a dropped stack draws one item per flush
     * in a loop, and every one of them is the same item form.
     */
    @Unique private boolean grug$unlitItem;

    @Inject(
            method =
                    "renderModel(Lnet/minecraft/entity/ItemEntity;FFFFLnet/minecraft/item/ItemStack;"
                        + "FFBLnet/modificationstation/stationapi/api/client/texture/"
                        + "SpriteAtlasTexture;Lnet/modificationstation/stationapi/api/client/"
                        + "render/model/BakedModel;)V",
            at = @At("HEAD"))
    private void grug$noteUnlit(
            ItemEntity entity,
            float x,
            float y,
            float z,
            float yaw,
            ItemStack stack,
            float brightness,
            float tickDelta,
            byte count,
            SpriteAtlasTexture atlas,
            BakedModel model,
            CallbackInfo ci) {
        grug$unlitItem = GrugModelRenderer.drawsUnlitItem(stack);
    }

    @Inject(
            method =
                    "renderModel(Lnet/minecraft/entity/ItemEntity;FFFFLnet/minecraft/item/ItemStack;"
                        + "FFBLnet/modificationstation/stationapi/api/client/texture/"
                        + "SpriteAtlasTexture;Lnet/modificationstation/stationapi/api/client/"
                        + "render/model/BakedModel;)V",
            at = @At("RETURN"))
    private void grug$clearUnlit(
            ItemEntity entity,
            float x,
            float y,
            float z,
            float yaw,
            ItemStack stack,
            float brightness,
            float tickDelta,
            byte count,
            SpriteAtlasTexture atlas,
            BakedModel model,
            CallbackInfo ci) {
        grug$unlitItem = false;
    }

    @Redirect(
            method =
                    "renderModel(Lnet/minecraft/entity/ItemEntity;FFFFLnet/minecraft/item/ItemStack;"
                        + "FFBLnet/modificationstation/stationapi/api/client/texture/"
                        + "SpriteAtlasTexture;Lnet/modificationstation/stationapi/api/client/"
                        + "render/model/BakedModel;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V"))
    private void grug$unlitFlush(Tessellator tessellator) {
        if (!grug$unlitItem) {
            tessellator.draw();
            return;
        }

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        try {
            tessellator.draw();
        } finally {
            GL11.glPopAttrib();
        }
    }
}
