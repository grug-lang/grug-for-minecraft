package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.world.WorldView;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a block that draws its own geometry stop hiding its neighbours' faces.
 *
 * <p>This version culls a face by asking the world whether the block next to it is solid, and the
 * world asks that block's own {@code isSolid}, which answers true unless a subclass says otherwise.
 * The {@code IS_SOLID} table the loader fills in is a cache the game never consults for that
 * question, so clearing it changed nothing: the pipe's neighbours lost the face that touched it,
 * and the block behind a pipe showed the sky through its missing faces (#577).
 *
 * <p>Overriding {@code isSolid} is not an option here, because {@code World.hasSignal} asks the
 * same method to decide whether a block conducts redstone, and a pipe or an engine that stopped
 * being solid stopped being powered by the torch under it. So the face check is answered here
 * instead: when the block the face looks at draws its own geometry, the face is visible, which is
 * what {@code shouldRenderFace} gives for any non-solid neighbour.
 */
@Mixin(Block.class)
public class BlockFaceMixin {

    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private void grug$customGeometryHidesNothing(
            WorldView world, int x, int y, int z, int side, CallbackInfoReturnable<Boolean> cir) {
        Block neighbour = Block.BY_ID[world.getBlock(x, y, z)];
        if (neighbour instanceof GrugBlock
                && ((GrugBlock) neighbour).drawsCustomGeometryOnceDeclared()) {
            cir.setReturnValue(true);
        }
    }
}
