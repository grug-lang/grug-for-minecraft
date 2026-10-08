package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.block.GrugBoxRenderer;
import net.minecraft.block.Block;
import net.minecraft.client.render.block.BlockRenderer;
import net.minecraft.world.WorldView;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
 * <p>{@code forcedSprite} is left alone rather than honoured: it is the mining-progress overlay,
 * which sets it and then tesselates a second copy of the block. Cancelling this call cancels that
 * second copy too, so a custom-rendered block being mined shows its geometry with no crack overlay
 * on it. That is the honest consequence of cancelling here, and it is left as it is rather than
 * special cased, because a crack drawn over custom geometry would need the shape applied twice.
 */
@Mixin(BlockRenderer.class)
public class BlockRendererMixin {

    @Shadow private WorldView world;

    @Inject(method = "tesselateInWorld", at = @At("HEAD"), cancellable = true)
    private void grug$customGeometry(
            Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (GrugBoxRenderer.render((BlockRenderer) (Object) this, world, block, x, y, z)) {
            // True, not false: the block did draw geometry, so the chunk is not empty. Returning
            // false would let the chunk compile skip the display list that now holds the shape.
            cir.setReturnValue(true);
        }
    }
}
