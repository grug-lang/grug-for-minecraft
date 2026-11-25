package net.grug.minecraft.forge125.client;

import net.grug.minecraft.forge125.block.GrugBlock;
import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.GrugBlockGeometry;
import net.grug.minecraft.grug.GrugFrameDraw;
import net.grug.minecraft.grug.GrugFrameGeometry;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemDraw;
import net.grug.minecraft.grug.GrugLiquidDraw;
import net.grug.minecraft.grug.GrugModelDraw;
import net.minecraft.src.Block;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.RenderBlocks;
import net.minecraft.src.RenderManager;
import net.minecraft.src.Tessellator;
import net.minecraft.src.TileEntity;
import net.minecraft.src.TileEntitySpecialRenderer;
import net.minecraft.src.World;
import net.minecraft.src.forge.ForgeHooksClient;

import org.lwjgl.opengl.GL11;

import java.util.Random;

/**
 * Draws what a grug block entity's {@code render_frame()} drew, on top of the chunk geometry, once
 * per frame.
 *
 * <p>The block's own shape is baked into its chunk's geometry by {@code render()}, which is built
 * once per rebuild and does not change with the camera or between ticks. A part that turns to face
 * the player, as BuildCraft's travelling items do, cannot go there, so it is drawn here instead,
 * through the same tile entity renderer hook this version draws signs and chests with. The hook
 * gets the block's position relative to the camera, which is where the frame pass's block-local
 * draws start from, and it runs with the block's own lightmap already set, so nothing here has to
 * work out the brightness.
 *
 * <p>The stack itself is drawn the way the reference's {@code RenderPipe.doRenderItem} draws it: a
 * block as a small cube lifted a quarter of a block, anything else as one flat sprite lifted a
 * tenth and turned to face the camera, with the reference's copy count and scatter. A model the
 * script drew with {@code draw_model} is drawn here too, at the block's camera-relative position,
 * which is how the reference's own tile entity renderers draw their boxes and where an edge that
 * moves every tick lands on the pixel the reference puts it on. Lighting is off for the whole pass,
 * as the reference turns it off around its item loop.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}: it draws into the
 * game's renderer. What it draws is decided by core's {@code GrugItemDraw}, which is measured
 * there, and by the screenshot tests.
 */
@GrugGenerated(
        "draws during the game's frame, so it is coupled to the renderer rather than to grug")
public class GrugFrameRenderer extends TileEntitySpecialRenderer {

    /** The block renderer the reference uses for a block item, with no world of its own. */
    private final RenderBlocks renderBlocks = new RenderBlocks();

    /**
     * The reference's own random, re-seeded per stack, which is what makes every stack's extra
     * copies land in the same places.
     */
    private final Random random = new Random();

    @Override
    public void renderTileEntityAt(
            TileEntity tile, double dx, double dy, double dz, float partialTicks) {
        if (!(tile instanceof GrugBlockEntity)) return;

        GrugBlockEntity blockEntity = (GrugBlockEntity) tile;
        World world = tile.worldObj;
        Block block = Block.blocksList[world.getBlockId(tile.xCoord, tile.yCoord, tile.zCoord)];
        if (!(block instanceof GrugBlock) || !((GrugBlock) block).drawsCustomGeometry()) {
            return;
        }

        float yaw = RenderManager.instance.playerViewY;
        GrugFrameGeometry geometry =
                GrugBlockGeometry.drawFrame(
                        blockEntity.getGrugEntityHandle(),
                        blockEntity.getFrameRenderFnId(),
                        partialTicks,
                        yaw);
        if (geometry.draws().isEmpty()) return;

        GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT
                        | GL11.GL_CURRENT_BIT
                        | GL11.GL_TEXTURE_BIT
                        | GL11.GL_LIGHTING_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        try {
            for (GrugFrameDraw draw : geometry.draws()) {
                if (draw instanceof GrugModelDraw) {
                    drawModel((GrugModelDraw) draw, dx, dy, dz, world, block, tile);
                } else if (draw instanceof GrugItemDraw) {
                    GrugItemDraw item = (GrugItemDraw) draw;
                    drawItem(item, dx + item.x(), dy + item.y(), dz + item.z(), yaw);
                } else if (draw instanceof GrugLiquidDraw) {
                    drawLiquid((GrugLiquidDraw) draw, dx, dy, dz);
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * Draws one model at the block's camera-relative position, moved by the model's own offset,
     * which is the chain {@code RenderEngine.render} builds: the block's position, then the part's
     * offset, then the boxes.
     */
    private void drawModel(
            GrugModelDraw draw,
            double dx,
            double dy,
            double dz,
            World world,
            Block block,
            TileEntity tile) {
        GL11.glPushMatrix();
        GL11.glTranslatef((float) dx, (float) dy, (float) dz);
        GL11.glTranslatef((float) draw.x(), (float) draw.y(), (float) draw.z());
        GrugModelRenderer.drawInFrame(
                world, block, tile.xCoord, tile.yCoord, tile.zCoord, draw.quads());
        GL11.glPopMatrix();
    }

    /**
     * Draws one stack at a camera-relative position, the way the reference's {@code doRenderItem}
     * draws a travelling item.
     */
    private void drawItem(GrugItemDraw draw, double x, double y, double z, float yaw) {
        // A mod may name a block rather than its item, which this version's item lookup answers
        // with the block itself. Drawing its item form is what the name means here, so both are
        // accepted.
        Item item = null;
        Block block = null;
        if (draw.item() instanceof Item) {
            item = (Item) draw.item();
            int itemId = item.shiftedIndex;
            block =
                    itemId >= 0 && itemId < Block.blocksList.length
                            ? Block.blocksList[itemId]
                            : null;
        } else if (draw.item() instanceof Block) {
            block = (Block) draw.item();
            item = Item.itemsList[block.blockID];
        }
        if (item == null) return;

        int copies = draw.copies();
        if (copies == 0) return;

        ItemStack stack = new ItemStack(item, 1, draw.damage());

        // The reference re-seeds its random for every stack, before any copy is drawn.
        random.setSeed(187L);
        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, (float) z);

        if (block != null && RenderBlocks.renderItemIn3d(block.getRenderType())) {
            double lift = 0.25;
            double scale = isFullSizeItemRenderType(block.getRenderType()) ? 0.5 : 0.25;
            GL11.glTranslatef(0.0F, (float) lift, 0.0F);
            bindTextureByName(block.getTextureFile());
            ForgeHooksClient.overrideTexture(block);
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
                renderBlocks.renderBlockAsItem(block, draw.damage(), 1.0F);
                GL11.glPopMatrix();
            }
        } else {
            double lift = 0.1;
            double scale = 0.5;
            int icon = stack.getIconIndex();
            GL11.glTranslatef(0.0F, (float) lift, 0.0F);
            GL11.glScalef((float) scale, (float) scale, (float) scale);
            if (block != null) {
                bindTextureByName(block.getTextureFile());
                ForgeHooksClient.overrideTexture(block);
            } else {
                bindTextureByName(item.getTextureFile());
                ForgeHooksClient.overrideTexture(item);
            }

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
                Tessellator tessellator = Tessellator.instance;
                tessellator.startDrawingQuads();
                tessellator.setNormal(0.0F, 1.0F, 0.0F);
                tessellator.addVertexWithUV(-0.5, -0.25, 0.0, u0, v1);
                tessellator.addVertexWithUV(0.5, -0.25, 0.0, u1, v1);
                tessellator.addVertexWithUV(0.5, 0.75, 0.0, u1, v0);
                tessellator.addVertexWithUV(-0.5, 0.75, 0.0, u0, v0);
                tessellator.draw();
                GL11.glPopMatrix();
            }
        }

        GL11.glPopMatrix();
    }

    /**
     * Whether a block's inventory shape fills an inventory slot, which the reference draws at twice
     * the size of the other blocks: render types 1, 19, 12 and 2 are the ones its own {@code
     * doRenderItem} names.
     */
    private static boolean isFullSizeItemRenderType(int renderType) {
        return renderType == 1 || renderType == 19 || renderType == 12 || renderType == 2;
    }

    /**
     * Draws the liquid a frame recorded as a box, with the still liquid's own terrain sprite, the
     * way the reference's {@code RenderEntityBlock} draws a tank's column: unlit, every face of the
     * box drawn, from the liquid's own texture rather than a texture the mod ships. The reference
     * builds that box once per stage into a display list; it is drawn here each frame, which is
     * simpler and costs a few quads.
     *
     * <p>A liquid this version has no still block for, such as BuildCraft's oil, draws nothing
     * rather than a box in another liquid's colours.
     */
    private void drawLiquid(GrugLiquidDraw draw, double dx, double dy, double dz) {
        int icon = stillIcon(draw.liquid());
        if (icon < 0) return;

        bindTextureByName("/terrain.png");

        // The sprite index is a slot in a 16-column grid, and a texture pack may scale every
        // sprite, so the tile size comes from the bound atlas rather than being 16 pixels.
        int atlasWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int atlasHeight =
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        int tileWidth = atlasWidth / 16;
        int tileHeight = atlasHeight / 16;
        float u0 = (float) (icon % 16 * tileWidth) / atlasWidth;
        float u1 = (float) (icon % 16 * tileWidth + tileWidth) / atlasWidth;
        float v0 = (float) (icon / 16 * tileHeight) / atlasHeight;
        float v1 = (float) (icon / 16 * tileHeight + tileHeight) / atlasHeight;

        float x1 = (float) draw.x1();
        float y1 = (float) draw.y1();
        float z1 = (float) draw.z1();
        float x2 = (float) draw.x2();
        float y2 = (float) draw.y2();
        float z2 = (float) draw.z2();

        GL11.glPushMatrix();
        GL11.glTranslatef((float) dx, (float) dy, (float) dz);
        // The column is seen through the tank's own glass faces, so both sides of the box are
        // drawn, as the reference's own box renderer draws every face of its box.
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();

        tessellator.setNormal(0.0F, -1.0F, 0.0F);
        tessellator.addVertexWithUV(x1, y1, z1, u0, v0);
        tessellator.addVertexWithUV(x1, y1, z2, u0, v1);
        tessellator.addVertexWithUV(x2, y1, z2, u1, v1);
        tessellator.addVertexWithUV(x2, y1, z1, u1, v0);

        tessellator.setNormal(0.0F, 1.0F, 0.0F);
        tessellator.addVertexWithUV(x1, y2, z1, u0, v0);
        tessellator.addVertexWithUV(x2, y2, z1, u1, v0);
        tessellator.addVertexWithUV(x2, y2, z2, u1, v1);
        tessellator.addVertexWithUV(x1, y2, z2, u0, v1);

        tessellator.setNormal(0.0F, 0.0F, -1.0F);
        tessellator.addVertexWithUV(x1, y1, z1, u0, v0);
        tessellator.addVertexWithUV(x2, y1, z1, u1, v0);
        tessellator.addVertexWithUV(x2, y2, z1, u1, v1);
        tessellator.addVertexWithUV(x1, y2, z1, u0, v1);

        tessellator.setNormal(0.0F, 0.0F, 1.0F);
        tessellator.addVertexWithUV(x1, y1, z2, u0, v0);
        tessellator.addVertexWithUV(x1, y2, z2, u0, v1);
        tessellator.addVertexWithUV(x2, y2, z2, u1, v1);
        tessellator.addVertexWithUV(x2, y1, z2, u1, v0);

        tessellator.setNormal(-1.0F, 0.0F, 0.0F);
        tessellator.addVertexWithUV(x1, y1, z1, u0, v0);
        tessellator.addVertexWithUV(x1, y2, z1, u0, v1);
        tessellator.addVertexWithUV(x1, y2, z2, u1, v1);
        tessellator.addVertexWithUV(x1, y1, z2, u1, v0);

        tessellator.setNormal(1.0F, 0.0F, 0.0F);
        tessellator.addVertexWithUV(x2, y1, z1, u0, v0);
        tessellator.addVertexWithUV(x2, y1, z2, u0, v1);
        tessellator.addVertexWithUV(x2, y2, z2, u1, v1);
        tessellator.addVertexWithUV(x2, y2, z1, u1, v0);

        tessellator.draw();
        GL11.glPopMatrix();
    }

    /**
     * The terrain sprite of a still liquid, or -1 for a liquid this version does not have. In this
     * version the source block is waterStill or lavaStill, whose index is the still texture; the
     * flowing block's index is one past it.
     */
    private static int stillIcon(String liquid) {
        if ("minecraft:water".equals(liquid)) return Block.waterStill.blockIndexInTexture;
        if ("minecraft:lava".equals(liquid)) return Block.lavaStill.blockIndexInTexture;
        return -1;
    }
}
