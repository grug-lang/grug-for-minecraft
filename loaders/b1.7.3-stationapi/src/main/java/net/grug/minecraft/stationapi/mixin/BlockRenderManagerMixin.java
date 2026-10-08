package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.block.GrugBoxRenderer;
import net.minecraft.block.Block;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.world.BlockView;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a grug block entity draw its own geometry in place of its block's cube.
 *
 * <p>{@code render} is where every block in a chunk gets turned into geometry, so this is the one
 * place that reaches all of them: a block that declares custom rendering is drawn here instead of
 * being tesselated from its render type. It runs inside a chunk compile, which is when this version
 * decides what a block looks like, and the Tesselator is already begun with the chunk's translation
 * pushed, which is what GrugBoxRenderer draws into.
 *
 * <p>The priority places this between the two StationAPI mixins on the same method. StationAPI
 * routes a block whose baked model is not the vanilla fallback to its own renderer at the head of
 * {@code render}, and cancels this version's tesselation for it; a grug block's model is such a
 * model, so without running first this hook would only ever fire on a reload where the model bake
 * had fallen back, and the shape would appear or not depending on the reload. Mixin applies lower
 * priorities first, so 750 runs after the flattening mixin's 500, which captures the block's light
 * emission that the vanilla tesselation reads, and before the renderer mixin's default 1000.
 *
 * <p>Cancelling is what keeps the block from also drawing its cube. A block that does not declare
 * custom rendering is left alone and returns false, so every other block, grug or vanilla, still
 * goes through the game's own path.
 *
 * <p>The mining overlay is not drawn for a custom-rendered block: the overlay calls the same {@code
 * render}, so cancelling here cancels its second copy of the block too. That is the honest
 * consequence of cancelling, and it applies to every loader. See #201.
 */
@Mixin(value = BlockRenderManager.class, priority = 750)
public class BlockRenderManagerMixin {

    @Shadow private BlockView blockView;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void grug$customGeometry(
            Block block, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (GrugBoxRenderer.render((BlockRenderManager) (Object) this, blockView, block, x, y, z)) {
            // True, not false: the block did draw geometry, so the chunk is not empty. Returning
            // false would let the chunk compile skip the display list that now holds the shape.
            cir.setReturnValue(true);
        }
    }
}
