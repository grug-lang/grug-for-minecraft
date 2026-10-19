package net.grug.minecraft.forge.block;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.model.BakedModelWrapper;

import java.util.List;

/**
 * Draws a block that declared set_flat_lighting with a block sheet when its item is rendered, so
 * its item form is unlit.
 *
 * <p>An item is drawn with an entity sheet by Forge's default, {@code Sheets.cutoutBlockSheet} or
 * {@code Sheets.solidItemSheet}, and the entity vertex shader multiplies the texture by {@code
 * minecraft_mix_light}, a per-face directional shade. BuildCraft 2.2.14's engine renderer draws
 * with GL lighting off, so every face is at full texture brightness; the block sheet's shader
 * lights a face from the lightmap alone, which the item renderer puts at full brightness. The item
 * model then reads the same as its reference.
 *
 * <p>The change is scoped to the block, so every other item keeps the entity sheet it had, and it
 * reaches the item in the GUI, in the hand and on the ground, which all render through the item
 * renderer.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}: it draws into the item
 * renderer. What it draws is the block's own item model, which the other loaders measure.
 */
@GrugGenerated("draws into the item renderer, so it is coupled to the renderer")
public class GrugItemBakedModel extends BakedModelWrapper<BakedModel> {

    public GrugItemBakedModel(BakedModel original) {
        super(original);
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block instanceof GrugBlock grugBlock && grugBlock.drawsFlatLighting()) {
                if (!DIAG_DONE) {
                    DIAG_DONE = true;
                    System.out.println("[GRUG-DIAG] 1206 getRenderTypes flat item " + stack);
                }
                return List.of(RenderType.cutout());
            }
        }
        return super.getRenderTypes(stack, fabulous);
    }

    private static boolean DIAG_DONE = false;
}
