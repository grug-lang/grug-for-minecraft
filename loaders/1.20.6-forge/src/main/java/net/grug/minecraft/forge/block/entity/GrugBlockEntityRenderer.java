package net.grug.minecraft.forge.block.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.grug.minecraft.forge.block.GrugBakedModel;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.grug.GrugBlockGeometry;
import net.grug.minecraft.grug.GrugFrameDraw;
import net.grug.minecraft.grug.GrugFrameGeometry;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemDraw;
import net.grug.minecraft.grug.GrugLiquidDraw;
import net.grug.minecraft.grug.GrugModelDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

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
 * port reads the same on every loader. A model the script drew with {@code draw_model} is drawn
 * here too, at the block entity the pose stack is already at, which is where this version's own
 * block entity renderers draw their boxes and where the vertices stay beside the block instead of
 * at its place in the world. The vertex light is the light at the block entity, which is what the
 * reference's lighting off leaves applied: BuildCraft disables GL lighting around the item loop but
 * the tile entity renderer's lightmap still darkens the pipe's items.
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
        BlockState state = blockEntity.getBlockState();
        if (!(state.getBlock() instanceof GrugBlock grugBlock)
                || !grugBlock.drawsCustomGeometry()) {
            return;
        }

        // The camera is already interpolated for this frame, so its yaw is the direction the
        // player looks in without the partial tick being applied again here.
        float yaw = Minecraft.getInstance().gameRenderer.getMainCamera().getYRot();
        GrugFrameGeometry geometry =
                GrugBlockGeometry.drawFrame(
                        blockEntity.getGrugEntityHandle(),
                        blockEntity.getFrameRenderFnId(),
                        partialTick,
                        yaw);
        if (geometry.draws().isEmpty()) return;

        for (GrugFrameDraw draw : geometry.draws()) {
            if (draw instanceof GrugModelDraw model) {
                GrugBakedModel.drawInFrame(model, poseStack, buffer, packedLight);
            } else if (draw instanceof GrugItemDraw item) {
                drawItem(blockEntity, item, poseStack, buffer, packedLight, yaw);
            } else if (draw instanceof GrugLiquidDraw liquid) {
                drawLiquid(blockEntity, liquid, poseStack, buffer);
            }
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
            int packedLight,
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
                                packedLight,
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
            vertex(vertices, pose, packedLight, -0.5F, -0.25F, sprite.getU0(), sprite.getV1());
            vertex(vertices, pose, packedLight, 0.5F, -0.25F, sprite.getU1(), sprite.getV1());
            vertex(vertices, pose, packedLight, 0.5F, 0.75F, sprite.getU1(), sprite.getV0());
            vertex(vertices, pose, packedLight, -0.5F, 0.75F, sprite.getU0(), sprite.getV0());
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** One corner of the sprite's quad, at the block entity's light, front towards positive z. */
    private static void vertex(
            VertexConsumer vertices,
            PoseStack.Pose pose,
            int packedLight,
            float x,
            float y,
            float u,
            float v) {
        vertices.vertex(pose, x, y, 0.0F)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    /**
     * Draws the liquid a frame recorded as a box, with the still liquid's own block atlas sprite,
     * the way the reference's {@code RenderEntityBlock} draws a tank's column: every face of the
     * box drawn, unlit, from the liquid's own texture rather than a texture the mod ships.
     *
     * <p>A liquid this version has no still sprite for, such as BuildCraft's oil, draws nothing
     * rather than a box in another liquid's colours.
     */
    private static void drawLiquid(
            GrugBlockEntity blockEntity,
            GrugLiquidDraw draw,
            PoseStack poseStack,
            MultiBufferSource buffer) {
        TextureAtlasSprite sprite = stillSprite(draw.liquid());
        if (sprite == null) return;

        // This version's still water is a grey sprite that the game tints with the biome's water
        // colour, while the reference draws the already blue still texture of 1.2.5, so the tank
        // tints water the way a water block is tinted and leaves every other liquid as it is.
        int rgb = 0xFFFFFF;
        if ("minecraft:water".equals(draw.liquid()) && blockEntity.getLevel() != null) {
            rgb =
                    BiomeColors.getAverageWaterColor(
                            blockEntity.getLevel(), blockEntity.getBlockPos());
        }

        VertexConsumer vertices = buffer.getBuffer(RenderType.translucent());
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        poseStack.pushPose();
        poseStack.translate(draw.x1(), draw.y1(), draw.z1());
        PoseStack.Pose pose = poseStack.last();

        float x1 = 0.0F;
        float y1 = 0.0F;
        float z1 = 0.0F;
        float x2 = (float) (draw.x2() - draw.x1());
        float y2 = (float) (draw.y2() - draw.y1());
        float z2 = (float) (draw.z2() - draw.z1());

        // RenderEntityBlock maps the box's own extent onto the sprite's sub-rectangle rather
        // than stretching the whole tile across it: a column 0.135 to 0.865 across shows the
        // sprite's columns 2.16 to 13.84, with 0.01 of a texel off the larger edge the way
        // RenderBlocks does, so every texel lands where the reference puts it.
        float spanU = u1 - u0;
        float spanV = v1 - v0;
        float bx1 = (float) draw.x1();
        float by1 = (float) draw.y1();
        float bz1 = (float) draw.z1();
        float bx2 = (float) draw.x2();
        float by2 = (float) draw.y2();
        float bz2 = (float) draw.z2();
        float uX1 = u0 + bx1 * spanU;
        float uX2 = u0 + (bx2 - 0.01F / 16.0F) * spanU;
        float uZ1 = u0 + bz1 * spanU;
        float uZ2 = u0 + (bz2 - 0.01F / 16.0F) * spanU;
        float vY1 = v0 + by1 * spanV;
        float vY2 = v0 + (by2 - 0.01F / 16.0F) * spanV;
        float vZ1 = v0 + bz1 * spanV;
        float vZ2 = v0 + (bz2 - 0.01F / 16.0F) * spanV;

        // Every face of the box is drawn, including the inward ones, because the column is seen
        // through the tank's own glass faces and the reference's box renderer draws all six. The
        // six faces keep RenderEntityBlock's own order and corner and UV mapping, including the
        // faces that are mirrored against the world axes.
        liquidVertex(vertices, pose, rgb, x1, y1, z2, uX1, vZ2);
        liquidVertex(vertices, pose, rgb, x1, y1, z1, uX1, vZ1);
        liquidVertex(vertices, pose, rgb, x2, y1, z1, uX2, vZ1);
        liquidVertex(vertices, pose, rgb, x2, y1, z2, uX2, vZ2);

        liquidVertex(vertices, pose, rgb, x2, y2, z2, uX2, vZ2);
        liquidVertex(vertices, pose, rgb, x2, y2, z1, uX2, vZ1);
        liquidVertex(vertices, pose, rgb, x1, y2, z1, uX1, vZ1);
        liquidVertex(vertices, pose, rgb, x1, y2, z2, uX1, vZ2);

        liquidVertex(vertices, pose, rgb, x1, y2, z1, uX2, vY1);
        liquidVertex(vertices, pose, rgb, x2, y2, z1, uX1, vY1);
        liquidVertex(vertices, pose, rgb, x2, y1, z1, uX1, vY2);
        liquidVertex(vertices, pose, rgb, x1, y1, z1, uX2, vY2);

        liquidVertex(vertices, pose, rgb, x1, y2, z2, uX1, vY1);
        liquidVertex(vertices, pose, rgb, x1, y1, z2, uX1, vY2);
        liquidVertex(vertices, pose, rgb, x2, y1, z2, uX2, vY2);
        liquidVertex(vertices, pose, rgb, x2, y2, z2, uX2, vY1);

        liquidVertex(vertices, pose, rgb, x1, y2, z2, uZ2, vY1);
        liquidVertex(vertices, pose, rgb, x1, y2, z1, uZ1, vY1);
        liquidVertex(vertices, pose, rgb, x1, y1, z1, uZ1, vY2);
        liquidVertex(vertices, pose, rgb, x1, y1, z2, uZ2, vY2);

        liquidVertex(vertices, pose, rgb, x2, y1, z2, uZ1, vY2);
        liquidVertex(vertices, pose, rgb, x2, y1, z1, uZ2, vY2);
        liquidVertex(vertices, pose, rgb, x2, y2, z1, uZ2, vY1);
        liquidVertex(vertices, pose, rgb, x2, y2, z2, uZ1, vY1);

        poseStack.popPose();
    }

    /**
     * One corner of a liquid box, at the block atlas, the liquid's colour and the full brightness
     * the reference draws a liquid at, since it turns GL lighting off around its column.
     */
    private static void liquidVertex(
            VertexConsumer vertices,
            PoseStack.Pose pose,
            int rgb,
            float x,
            float y,
            float z,
            float u,
            float v) {
        vertices.vertex(pose, x, y, z)
                .color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    /**
     * The block atlas sprite of a still liquid, or null for a liquid this version does not have.
     * The sprite names the still frame, which is what the reference's tank column is drawn with.
     */
    private static TextureAtlasSprite stillSprite(String liquid) {
        String path;
        if ("minecraft:water".equals(liquid)) {
            path = "block/water_still";
        } else if ("minecraft:lava".equals(liquid)) {
            path = "block/lava_still";
        } else {
            return null;
        }
        return Minecraft.getInstance()
                .getModelManager()
                .getAtlas(InventoryMenu.BLOCK_ATLAS)
                .getSprite(new ResourceLocation("minecraft", path));
    }
}
