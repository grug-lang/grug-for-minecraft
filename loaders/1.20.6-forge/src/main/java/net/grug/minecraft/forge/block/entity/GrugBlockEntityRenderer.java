package net.grug.minecraft.forge.block.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.grug.minecraft.grug.GrugBlockGeometry;
import net.grug.minecraft.grug.GrugFrameGeometry;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws what a grug block entity's {@code render_frame()} drew, on top of the chunk geometry, once
 * per frame.
 *
 * <p>The block's own shape is baked into its chunk's geometry by {@code render()}, which is built
 * once per rebuild and does not change with the camera or between ticks. A part that turns to face
 * the player, as BuildCraft's travelling items do, cannot go there, so it is drawn here instead,
 * through the block entity renderer this version draws its own block entities with. The pose stack
 * is already at the block entity and relative to the camera, which is where the frame pass's
 * block-local draws start from.
 *
 * <p>The stack is drawn the way this version draws a dropped item: a block through the item
 * renderer's own model, which is the block's inventory model, and anything else as the item's icon
 * on one flat quad turned to face the camera. The reference's size and lift are applied here so the
 * port reads the same on every loader, and the vertex light is the full-bright value so the stack
 * is not darkened by the world's light, which is the reference's lighting off.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}: it draws into the
 * game's renderer. What it draws is decided by core's {@code GrugItemDraw}, which is measured
 * there, and by the screenshot tests.
 */
@GrugGenerated(
        "draws during the game's frame, so it is coupled to the renderer rather than to grug")
public class GrugBlockEntityRenderer implements BlockEntityRenderer<GrugBlockEntity> {

    @Override
    public void render(
            GrugBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            int packedOverlay) {
        // The camera is already interpolated for this frame, so its yaw is the direction the
        // player looks in without the partial tick being applied again here.
        float yaw = Minecraft.getInstance().gameRenderer.getMainCamera().getYRot();
        GrugFrameGeometry geometry =
                GrugBlockGeometry.drawFrame(
                        blockEntity.getGrugEntityHandle(),
                        blockEntity.getFrameRenderFnId(),
                        partialTick,
                        yaw);
        if (geometry.items().isEmpty()) return;

        for (GrugItemDraw draw : geometry.items()) {
            drawItem(blockEntity, draw, poseStack, buffer, yaw);
        }
    }

    /**
     * Draws one stack, at the pose stack's current block entity, the way this version draws a
     * dropped item.
     */
    private void drawItem(
            GrugBlockEntity blockEntity,
            GrugItemDraw draw,
            PoseStack poseStack,
            MultiBufferSource buffer,
            float yaw) {
        // A mod may name a block rather than its item. This version's lookup always answers with
        // the item, but a block is accepted and drawn as its item form rather than ignored.
        ItemStack stack;
        if (draw.item() instanceof net.minecraft.world.item.Item item) {
            stack = new ItemStack(item, 1);
        } else if (draw.item() instanceof net.minecraft.world.level.block.Block block) {
            stack = new ItemStack(block.asItem(), 1);
        } else {
            return;
        }
        stack.setDamageValue(draw.damage());

        int copies = draw.copies();
        if (copies == 0) return;

        if (stack.getItem() instanceof BlockItem) {
            double lift = 0.25;
            double scale = 0.25;
            poseStack.pushPose();
            poseStack.translate(draw.x(), draw.y() + lift, draw.z());
            poseStack.scale((float) scale, (float) scale, (float) scale);
            for (int copy = 0; copy < copies; copy++) {
                poseStack.pushPose();
                if (copy > 0) {
                    double[] scatter = GrugItemDraw.scatter(copy);
                    // The reference scatters a block inside the already-scaled matrix, so the
                    // divide by the scale is what makes every block move the same distance on
                    // screen whatever its size.
                    poseStack.translate(
                            scatter[0] * 0.2 / scale,
                            scatter[1] * 0.2 / scale,
                            scatter[2] * 0.2 / scale);
                }
                Minecraft.getInstance()
                        .getItemRenderer()
                        .renderStatic(
                                stack,
                                ItemDisplayContext.NONE,
                                LightTexture.FULL_BRIGHT,
                                OverlayTexture.NO_OVERLAY,
                                poseStack,
                                buffer,
                                blockEntity.getLevel(),
                                0);
                poseStack.popPose();
            }
            poseStack.popPose();
            return;
        }

        double lift = 0.1;
        double scale = 0.5;
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, blockEntity.getLevel(), null, 0);
        TextureAtlasSprite sprite = model.getParticleIcon();
        VertexConsumer vertices = buffer.getBuffer(RenderType.entityCutout(sprite.atlasLocation()));

        poseStack.pushPose();
        poseStack.translate(draw.x(), draw.y() + lift, draw.z());
        poseStack.scale((float) scale, (float) scale, (float) scale);
        for (int copy = 0; copy < copies; copy++) {
            poseStack.pushPose();
            if (copy > 0) {
                double[] scatter = GrugItemDraw.scatter(copy);
                poseStack.translate(scatter[0] * 0.3, scatter[1] * 0.3, scatter[2] * 0.3);
            }
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
            PoseStack.Pose pose = poseStack.last();
            vertex(vertices, pose, -0.5F, -0.25F, sprite.getU0(), sprite.getV1());
            vertex(vertices, pose, 0.5F, -0.25F, sprite.getU1(), sprite.getV1());
            vertex(vertices, pose, 0.5F, 0.75F, sprite.getU1(), sprite.getV0());
            vertex(vertices, pose, -0.5F, 0.75F, sprite.getU0(), sprite.getV0());
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** One corner of the sprite's quad, full bright, with its front towards positive z. */
    private static void vertex(
            VertexConsumer vertices, PoseStack.Pose pose, float x, float y, float u, float v) {
        vertices.vertex(pose, x, y, 0.0F)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
