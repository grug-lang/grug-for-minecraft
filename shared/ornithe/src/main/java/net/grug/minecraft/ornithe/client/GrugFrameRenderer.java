package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.grug.GrugBlockGeometry;
import net.grug.minecraft.grug.GrugFrameDraw;
import net.grug.minecraft.grug.GrugFrameGeometry;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemDraw;
import net.grug.minecraft.grug.GrugModelDraw;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.GrugBoxRenderer;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.texture.TextureManager;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldView;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

import org.lwjgl.opengl.GL11;

/**
 * Draws what a grug block entity's {@code render_frame()} drew, on top of the chunk geometry, once
 * per frame.
 *
 * <p>The block's own shape is baked into its chunk's geometry by {@code render()}, which is built
 * once per rebuild and does not change with the camera or between ticks. A part that turns to face
 * the player, as BuildCraft's travelling items do, cannot go there, so it is drawn here instead,
 * through the same block entity renderer this version draws signs and chests with. The hook gets
 * the block's position relative to the camera, which is where the frame pass's block-local draws
 * start from. Beta's dispatcher sets the block's brightness as the colour rather than through the
 * lightmap, and the block-as-item renderer would otherwise leave it white, so the frame pass reads
 * the brightness itself and applies it to both the cube and the sprite.
 *
 * <p>The stack itself is drawn the way the reference's {@code RenderPipe.doRenderItem} draws it: a
 * block as a small cube lifted a quarter of a block, anything else as one flat sprite lifted a
 * tenth and turned to face the camera, with the reference's copy count and scatter. Lighting is off
 * for the whole pass, as the reference turns it off around its item loop. Alpha and Beta differ
 * only in how the game draws a block as an item, which {@link GrugBoxRenderer} answers for each of
 * them.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}: it draws into the
 * game's renderer. What it draws is decided by core's {@code GrugItemDraw}, which is measured
 * there, and by the screenshot tests.
 */
@GrugGenerated(
        "draws during the game's frame, so it is coupled to the renderer rather than to grug")
public class GrugFrameRenderer extends BlockEntityRenderer {

    /** The atlas an item that is not a block draws its sprite from. */
    private static final String ITEMS_ATLAS = "/gui/items.png";

    /** The atlas a block that is not a cube draws its sprite from. */
    private static final String TERRAIN_ATLAS = "/terrain.png";

    @Override
    public void render(BlockEntity blockEntity, double dx, double dy, double dz, float tickDelta) {
        if (!(blockEntity instanceof GrugBlockEntity)) return;

        GrugBlockEntity grugBlockEntity = (GrugBlockEntity) blockEntity;
        if (grugBlockEntity.world == null) return;

        Block block =
                Block.BY_ID[
                        grugBlockEntity.world.getBlock(
                                grugBlockEntity.x, grugBlockEntity.y, grugBlockEntity.z)];
        if (!(block instanceof GrugBlock) || !((GrugBlock) block).drawsCustomGeometry()) {
            return;
        }

        float yaw = cameraYaw(tickDelta);
        GrugFrameGeometry geometry =
                GrugBlockGeometry.drawFrame(
                        grugBlockEntity.getGrugEntityHandle(),
                        grugBlockEntity.getFrameRenderFnId(),
                        tickDelta,
                        yaw);
        if (geometry.draws().isEmpty()) return;

        GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT
                        | GL11.GL_CURRENT_BIT
                        | GL11.GL_TEXTURE_BIT
                        | GL11.GL_LIGHTING_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        try {
            float brightness =
                    grugBlockEntity.world.getBrightness(
                            grugBlockEntity.x, grugBlockEntity.y, grugBlockEntity.z);
            for (GrugFrameDraw draw : geometry.draws()) {
                if (draw instanceof GrugModelDraw) {
                    drawModel(
                            (GrugModelDraw) draw,
                            dx,
                            dy,
                            dz,
                            grugBlockEntity.world,
                            block,
                            grugBlockEntity);
                } else if (draw instanceof GrugItemDraw) {
                    GrugItemDraw item = (GrugItemDraw) draw;
                    drawItem(item, dx + item.x(), dy + item.y(), dz + item.z(), yaw, brightness);
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * Draws one model at the block's camera-relative position, moved by the model's own offset,
     * which is the chain a reference's block entity renderer builds: the block's position, then the
     * part's offset, then the boxes.
     */
    private static void drawModel(
            GrugModelDraw draw,
            double dx,
            double dy,
            double dz,
            WorldView world,
            Block block,
            GrugBlockEntity blockEntity) {
        GL11.glPushMatrix();
        GL11.glTranslatef((float) dx, (float) dy, (float) dz);
        GL11.glTranslatef((float) draw.x(), (float) draw.y(), (float) draw.z());
        GrugModelRenderer.drawInFrame(
                world, block, blockEntity.x, blockEntity.y, blockEntity.z, draw.quads());
        GL11.glPopMatrix();
    }

    /**
     * The direction the camera looks in, interpolated between the previous tick and this one, as
     * the reference's render manager publishes it.
     *
     * <p>The player is the render view entity in first person, which is where every test draws
     * from. A frame with no player yet is drawn facing positive z rather than not at all, so a
     * sprite is never left at whatever rotation the last one used.
     */
    private static float cameraYaw(float tickDelta) {
        Minecraft minecraft = MinecraftInstance.get();
        Entity player = minecraft == null ? null : minecraft.player;
        if (player == null) return 0.0F;
        return player.lastYaw + (player.yaw - player.lastYaw) * tickDelta;
    }

    /**
     * Draws one stack at a camera-relative position, the way the reference's {@code doRenderItem}
     * draws a travelling item.
     */
    private void drawItem(
            GrugItemDraw draw, double x, double y, double z, float yaw, float brightness) {
        // A mod may name a block rather than its item, which this version's item lookup answers
        // with the block itself. Drawing its item form is what the name means here, so both are
        // accepted.
        Item item = null;
        Block block = null;
        if (draw.item() instanceof Item) {
            item = (Item) draw.item();
            block = item.id >= 0 && item.id < Block.BY_ID.length ? Block.BY_ID[item.id] : null;
        } else if (draw.item() instanceof Block) {
            block = (Block) draw.item();
            item = Item.BY_ID[block.id];
        }
        if (item == null) return;

        int copies = draw.copies();
        if (copies == 0) return;

        ItemStack stack = new ItemStack(item.id, 1, draw.damage());
        TextureManager textures = MinecraftInstance.get().textureManager;

        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, (float) z);

        if (block != null && GrugBoxRenderer.isFullItem(block)) {
            double lift = 0.25;
            double scale = GrugBoxRenderer.isFullSizeItemRenderType(block) ? 0.5 : 0.25;
            GL11.glTranslatef(0.0F, (float) lift, 0.0F);
            textures.bind(textures.load(TERRAIN_ATLAS));
            GL11.glScalef((float) scale, (float) scale, (float) scale);

            for (int copy = 0; copy < copies; copy++) {
                GL11.glPushMatrix();
                if (copy > 0) {
                    double[] scatter = GrugItemDraw.scatter(copy);
                    // The reference scatters a block inside the already-scaled matrix, so the
                    // divide by the scale is what makes every block move the same distance on
                    // screen whatever its size.
                    GL11.glTranslatef(
                            (float) (scatter[0] * 0.2 / scale),
                            (float) (scatter[1] * 0.2 / scale),
                            (float) (scatter[2] * 0.2 / scale));
                }
                GrugBoxRenderer.drawBlockAsItem(block, draw.damage(), brightness);
                GL11.glPopMatrix();
            }
        } else {
            double lift = 0.1;
            double scale = 0.5;
            int icon = stack.getSprite();
            GL11.glColor3f(brightness, brightness, brightness);
            GL11.glTranslatef(0.0F, (float) lift, 0.0F);
            GL11.glScalef((float) scale, (float) scale, (float) scale);
            textures.bind(textures.load(block != null ? TERRAIN_ATLAS : ITEMS_ATLAS));

            // The sprite index is a slot in a 16-column grid. A texture pack may scale every
            // sprite, so the tile size comes from the bound atlas rather than being 16 pixels:
            // one sixteenth of each axis, with the grid still 16 columns.
            int atlasWidth =
                    GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int atlasHeight =
                    GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            int tileWidth = atlasWidth / 16;
            int tileHeight = atlasHeight / 16;
            float u0 = (float) (icon % 16 * tileWidth) / atlasWidth;
            float u1 = (float) (icon % 16 * tileWidth + tileWidth) / atlasWidth;
            float v0 = (float) (icon / 16 * tileHeight) / atlasHeight;
            float v1 = (float) (icon / 16 * tileHeight + tileHeight) / atlasHeight;
            for (int copy = 0; copy < copies; copy++) {
                GL11.glPushMatrix();
                if (copy > 0) {
                    double[] scatter = GrugItemDraw.scatter(copy);
                    GL11.glTranslatef(
                            (float) (scatter[0] * 0.3),
                            (float) (scatter[1] * 0.3),
                            (float) (scatter[2] * 0.3));
                }
                GL11.glRotatef(180.0F - yaw, 0.0F, 1.0F, 0.0F);
                Tesselator tesselator = Tesselator.INSTANCE;
                tesselator.begin();
                tesselator.normal(0.0F, 1.0F, 0.0F);
                tesselator.vertex(-0.5, -0.25, 0.0, u0, v1);
                tesselator.vertex(0.5, -0.25, 0.0, u1, v1);
                tesselator.vertex(0.5, 0.75, 0.0, u1, v0);
                tesselator.vertex(-0.5, 0.75, 0.0, u0, v0);
                tesselator.end();
                GL11.glPopMatrix();
            }
        }

        GL11.glPopMatrix();
    }
}
