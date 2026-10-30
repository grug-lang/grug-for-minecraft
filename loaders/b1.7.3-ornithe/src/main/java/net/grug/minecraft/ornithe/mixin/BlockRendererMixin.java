package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.block.GrugBoxRenderer;
import net.grug.minecraft.ornithe.client.GrugModelRenderer;
import net.minecraft.block.Block;
import net.minecraft.client.render.block.BlockRenderer;
import net.minecraft.world.WorldView;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a grug block entity draw its own geometry in place of its block's cube.
 *
 * <p>{@code tesselateInWorld} is where every block in a chunk gets turned into geometry, so this is
 * the one place that reaches all of them: a block that declares custom rendering is drawn here
 * instead of being tesselated from its render type. It runs inside a chunk compile, which is when
 * this version decides what a block looks like, and the Tesselator is already begun with the
 * chunk's translation pushed, which is what GrugBoxRenderer draws into.
 *
 * <p>Cancelling is what keeps the block from also drawing its cube. A block that does not declare
 * custom rendering is left alone and returns false, so every other block, grug or vanilla, still
 * goes through the game's own path.
 *
 * <p>The mining-progress overlay sets {@code forcedSprite} and then tesselates the block a second
 * time, and the face methods of {@code tesselateBlock}, which is what this class hands each box to,
 * use that sprite instead of the block's own. So the boxes are drawn with the crack on them, and
 * the only copy this cancels is the one {@code tesselateInWorld} would have drawn from the render
 * type, which is the cube the block does not use. See #201.
 */
@Mixin(BlockRenderer.class)
public class BlockRendererMixin {

    @Shadow private WorldView world;

    /** The destroy-stage sprite while the mining overlay draws, and -1 otherwise. */
    @Shadow private int forcedSprite;

    @Inject(method = "tesselateInWorld", at = @At("HEAD"), cancellable = true)
    private void grug$customGeometry(
            Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (GrugBoxRenderer.render(
                (BlockRenderer) (Object) this, world, block, x, y, z, forcedSprite >= 0)) {
            // True, not false: the block did draw geometry, so the chunk is not empty. Returning
            // false would let the chunk compile skip the display list that now holds the shape.
            cir.setReturnValue(true);
        }
    }

    /**
     * Draws a custom-rendered grug block's item form from its item model, in the inventory, in the
     * hand and on the ground, which all draw a block item through here.
     */
    @Inject(method = "renderAsItem", at = @At("HEAD"), cancellable = true)
    private void grug$customItem(Block block, int metadata, float brightness, CallbackInfo ci) {
        if (GrugModelRenderer.drawItemForm(block)) {
            ci.cancel();
        }
    }
}
