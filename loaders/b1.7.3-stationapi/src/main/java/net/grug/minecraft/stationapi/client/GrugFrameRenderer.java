package net.grug.minecraft.stationapi.client;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugBlockGeometry;
import net.grug.minecraft.grug.GrugFrameGeometry;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemDraw;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.GrugBoxRenderer;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.BlockView;
import net.modificationstation.stationapi.api.client.StationRenderAPI;
import net.modificationstation.stationapi.api.client.texture.Sprite;
import net.modificationstation.stationapi.api.client.texture.SpriteAtlasTexture;
import net.modificationstation.stationapi.api.client.texture.atlas.Atlases;
import net.modificationstation.stationapi.api.client.texture.atlas.CustomAtlasProvider;

import org.lwjgl.opengl.GL11;

/**
 * Draws what a grug block entity's {@code render_frame()} drew, on top of the chunk geometry, once
 * per frame.
 *
 * <p>The block's own shape is baked into its chunk's geometry by {@code render()}, which is built
 * once per rebuild and does not change with the camera or between ticks. A part that turns to face
 * the player, as BuildCraft's travelling items do, cannot go there, so it is drawn here instead,
 * through StationAPI's block entity renderer, which is the same hook this version draws signs and
 * chests with. The hook gets the block's position relative to the camera, which is where the frame
 * pass's block-local draws start from. The block-as-item renderer would otherwise draw at full
 * brightness, so the frame pass reads the block's brightness itself and applies it to both the cube
 * and the sprite.
 *
 * <p>The stack itself is drawn the way the reference's {@code RenderPipe.doRenderItem} draws it: a
 * block as a small cube lifted a quarter of a block, anything else as one flat sprite lifted a
 * tenth and turned to face the camera, with the reference's copy count and scatter. Lighting is off
 * for the whole pass, as the reference turns it off around its item loop.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}: it draws into the
 * game's renderer. What it draws is decided by core's {@code GrugItemDraw}, which is measured
 * there, and by the screenshot tests.
 */
@GrugGenerated(
        "draws during the game's frame, so it is coupled to the renderer rather than to grug")
public class GrugFrameRenderer extends BlockEntityRenderer {

    /** The atlas a block item drawn as a cube samples its faces from. */
    private static final String TERRAIN_ATLAS = "/terrain.png";

    /** The world the cached block renderer belongs to, or null before the first frame. */
    private BlockView renderedWorld = null;

    /**
     * The renderer a block item is drawn with. It holds the world it reads block shapes from, so it
     * is rebuilt when the world changes rather than once per item drawn.
     */
    private BlockRenderManager blockItemRenderer = null;

    @Override
    public void render(BlockEntity blockEntity, double dx, double dy, double dz, float tickDelta) {
        if (!(blockEntity instanceof GrugBlockEntity)) return;

        GrugBlockEntity grugBlockEntity = (GrugBlockEntity) blockEntity;
        if (grugBlockEntity.world == null) return;

        Block block =
                Block.BLOCKS[
                        grugBlockEntity.world.getBlockId(
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
        if (geometry.items().isEmpty()) return;

        // The dispatcher colours a block entity with method_1782, which this mapping leaves under
        // its intermediary name. World.getBrightness is the integer light level instead, so using
        // it here overflows the 0..1 colour and draws the items full bright.
        float brightness =
                grugBlockEntity.world.method_1782(
                        grugBlockEntity.x, grugBlockEntity.y, grugBlockEntity.z);

        GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT
                        | GL11.GL_CURRENT_BIT
                        | GL11.GL_TEXTURE_BIT
                        | GL11.GL_LIGHTING_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        try {
            for (GrugItemDraw draw : geometry.items()) {
                drawItem(
                        grugBlockEntity.world,
                        draw,
                        dx + draw.x(),
                        dy + draw.y(),
                        dz + draw.z(),
                        yaw,
                        brightness);
            }
        } finally {
            GL11.glPopAttrib();
        }
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
        Minecraft minecraft = (Minecraft) FabricLoader.getInstance().getGameInstance();
        ClientPlayerEntity player = minecraft == null ? null : minecraft.player;
        if (player == null) return 0.0F;
        return player.prevYaw + (player.yaw - player.prevYaw) * tickDelta;
    }

    /**
     * Draws one stack at a camera-relative position, the way the reference's {@code doRenderItem}
     * draws a travelling item.
     */
    private void drawItem(
            BlockView world,
            GrugItemDraw draw,
            double x,
            double y,
            double z,
            float yaw,
            float brightness) {
        // A mod may name a block rather than its item, which this version's item lookup answers
        // with the block itself. Drawing its item form is what the name means here, so both are
        // accepted.
        Item item = null;
        Block block = null;
        if (draw.item() instanceof Item) {
            item = (Item) draw.item();
            // StationAPI gives items their own id space, so an item's id is not the id of the
            // block it places. A block item names its block directly.
            if (item instanceof net.minecraft.item.BlockItem) {
                block =
                        Block.BLOCKS[
                                ((net.grug.minecraft.stationapi.mixin.BlockItemMixin) item)
                                        .grug$blockId()];
            }
        } else if (draw.item() instanceof Block) {
            block = (Block) draw.item();
            item = Item.ITEMS[block.id];
        }
        if (item == null) return;

        int copies = draw.copies();
        if (copies == 0) return;

        ItemStack stack = new ItemStack(item.id, 1, draw.damage());
        TextureManager textures =
                ((Minecraft) FabricLoader.getInstance().getGameInstance()).textureManager;

        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, (float) z);

        if (block != null && GrugBoxRenderer.isFullItem(block)) {
            double lift = 0.25;
            double scale = GrugBoxRenderer.isFullSizeItemRenderType(block) ? 0.5 : 0.25;
            GL11.glTranslatef(0.0F, (float) lift, 0.0F);
            textures.bindTexture(textures.getTextureId(TERRAIN_ATLAS));
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
                GrugBoxRenderer.drawBlockAsItem(
                        blockItemRenderer(world), block, draw.damage(), brightness);
                GL11.glPopMatrix();
            }
        } else {
            double lift = 0.1;
            double scale = 0.5;
            int icon = stack.getTextureId();
            GL11.glColor3f(brightness, brightness, brightness);
            GL11.glTranslatef(0.0F, (float) lift, 0.0F);
            GL11.glScalef((float) scale, (float) scale, (float) scale);

            // StationAPI stitches the pack's sprites into one game atlas, so an item's texture
            // index is not a slot in a 256x256 items.png. Ask the same atlas the reference's item
            // entity renderer asks for the sprite the index names, rather than recomputing UVs
            // from the index.
            SpriteAtlasTexture atlas =
                    StationRenderAPI.getBakedModelManager().getAtlas(Atlases.GAME_ATLAS_TEXTURE);
            atlas.bindTexture();
            Sprite sprite =
                    atlas.getSprite(
                            ((CustomAtlasProvider) item).getAtlas().getTexture(icon).getId());
            float u0 = sprite.getMinU();
            float u1 = sprite.getMaxU();
            float v0 = sprite.getMinV();
            float v1 = sprite.getMaxV();
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
                Tessellator tessellator = Tessellator.INSTANCE;
                tessellator.startQuads();
                tessellator.normal(0.0F, 1.0F, 0.0F);
                tessellator.vertex(-0.5, -0.25, 0.0, u0, v1);
                tessellator.vertex(0.5, -0.25, 0.0, u1, v1);
                tessellator.vertex(0.5, 0.75, 0.0, u1, v0);
                tessellator.vertex(-0.5, 0.75, 0.0, u0, v0);
                tessellator.draw();
                GL11.glPopMatrix();
            }
        }

        GL11.glPopMatrix();
    }

    /** The block item renderer for {@code world}, rebuilding it when the world changed. */
    private BlockRenderManager blockItemRenderer(BlockView world) {
        if (blockItemRenderer == null || renderedWorld != world) {
            renderedWorld = world;
            blockItemRenderer = new BlockRenderManager(world);
        }
        return blockItemRenderer;
    }
}
