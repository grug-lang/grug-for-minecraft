package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.client.GrugModelRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.modificationstation.stationapi.impl.client.arsenic.renderer.render.ArsenicOverlayRenderer;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a grug block's item form in the hand and in third person with OpenGL lighting off, as
 * 2.2.14 does.
 *
 * <p>2.2.14's {@code RenderEngine.render}, which every one of its item render types goes through,
 * turns lighting off, so the engine's item is unshaded wherever it is drawn. This loader's item
 * renderer accumulates the model's quads into the tessellator and flushes them after {@code
 * renderItem} returns, and fixed-function lighting is applied at that flush, so the disable has to
 * span it. {@link GrugModelRenderer#drawsUnlitItem(ItemStack)} names the items it applies to; here
 * it is evaluated once, when a draw starts, so the flush cannot disagree with it.
 *
 * <p>This class draws the two world paths a held item takes: {@code renderModel} over a living
 * entity for third person, and over the client player for the hand. A vanilla model goes through
 * {@code renderVanilla}, which cannot be a grug item.
 */
@Mixin(ArsenicOverlayRenderer.class)
public class ArsenicOverlayRendererMixin {

    /** Whether the third-person draw that is running now is one the reference lights off. */
    @Unique private boolean grug$unlitWorldItem;

    /** Whether the hand draw that is running now is one the reference lights off. */
    @Unique private boolean grug$unlitHandItem;

    @Inject(
            method =
                    "renderModel(Lnet/minecraft/entity/LivingEntity;"
                            + "Lnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"))
    private void grug$noteWorldUnlit(LivingEntity entity, ItemStack stack, CallbackInfo ci) {
        grug$unlitWorldItem = GrugModelRenderer.drawsUnlitItem(stack);
    }

    @Redirect(
            method =
                    "renderModel(Lnet/minecraft/entity/LivingEntity;"
                            + "Lnet/minecraft/item/ItemStack;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V"))
    private void grug$unlitWorldFlush(Tessellator tessellator) {
        if (!grug$unlitWorldItem) {
            tessellator.draw();
            return;
        }

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        try {
            tessellator.draw();
        } finally {
            GL11.glPopAttrib();
            grug$unlitWorldItem = false;
        }
    }

    @Inject(
            method =
                    "renderModel(FFLnet/minecraft/entity/player/ClientPlayerEntity;"
                            + "Lnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"))
    private void grug$noteHandUnlit(
            float x, float y, ClientPlayerEntity player, ItemStack stack, CallbackInfo ci) {
        grug$unlitHandItem = GrugModelRenderer.drawsUnlitItem(stack);
    }

    @Redirect(
            method =
                    "renderModel(FFLnet/minecraft/entity/player/ClientPlayerEntity;"
                            + "Lnet/minecraft/item/ItemStack;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V"))
    private void grug$unlitHandFlush(Tessellator tessellator) {
        if (!grug$unlitHandItem) {
            tessellator.draw();
            return;
        }

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        try {
            tessellator.draw();
        } finally {
            GL11.glPopAttrib();
            grug$unlitHandItem = false;
        }
    }
}
