package net.grug.minecraft.forge125.client;

import net.grug.minecraft.forge125.block.GrugBlock;
import net.grug.minecraft.forge125.block.GrugBlocks;
import net.grug.minecraft.forge125.mod_Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.Block;
import net.minecraft.src.ItemStack;
import net.minecraft.src.forge.IItemRenderer;
import net.minecraft.src.forge.MinecraftForgeClient;

import org.lwjgl.opengl.GL11;

/**
 * Draws a custom-rendered grug block's item form from its item model, through Forge's item renderer
 * hook, which is how a 1.2.5 block item draws something other than its cube.
 *
 * <p>Forge sets up the same transforms it would for the block's cube, so the model is drawn the way
 * {@code RenderBlocks.renderBlockAsItem} would draw that cube: centred on the origin, except in the
 * hand, where Forge has already moved the origin to the cube's corner.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}: it draws into the
 * game's item renderer. What it draws is core's {@code GrugModels}, which is measured there.
 */
@GrugGenerated("draws into the item renderer, so it is coupled to the renderer")
public final class GrugItemRenderer implements IItemRenderer {

    private static boolean registered = false;

    /** Registers the renderer for every grug block that draws its own geometry, once. */
    public static void register() {
        if (registered) return;
        // Registration is lazy, so the blocks may not exist yet; this is what makes them exist.
        GrugBlocks.init();
        if (mod_Grug.blockFiles.isEmpty()) return;
        registered = true;

        GrugItemRenderer renderer = new GrugItemRenderer();
        for (Block block : Block.blocksList) {
            if (block instanceof GrugBlock && ((GrugBlock) block).drawsCustomGeometry()) {
                MinecraftForgeClient.registerItemRenderer(block.blockID, renderer);
            }
        }
    }

    @Override
    public boolean handleRenderType(ItemStack item, ItemRenderType type) {
        return type != ItemRenderType.FIRST_PERSON_MAP;
    }

    @Override
    public boolean shouldUseRenderHelper(
            ItemRenderType type, ItemStack item, ItemRendererHelper helper) {
        return helper == ItemRendererHelper.INVENTORY_BLOCK
                || helper == ItemRendererHelper.EQUIPPED_BLOCK
                || helper == ItemRendererHelper.BLOCK_3D
                || helper == ItemRendererHelper.ENTITY_ROTATION
                || helper == ItemRendererHelper.ENTITY_BOBBING;
    }

    @Override
    public void renderItem(ItemRenderType type, ItemStack item, Object... data) {
        Block block = Block.blocksList[item.itemID];
        GL11.glPushMatrix();
        if (type == ItemRenderType.EQUIPPED) {
            // Forge has moved the origin to the cube's corner for the hand already.
            GL11.glTranslatef(0.5F, 0.5F, 0.5F);
        }
        GrugModelRenderer.drawItemForm(block);
        GL11.glPopMatrix();
    }
}
