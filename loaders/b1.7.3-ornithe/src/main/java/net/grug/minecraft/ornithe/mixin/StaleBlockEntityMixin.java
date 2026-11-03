package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.WorldChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops a grug block entity that outlived its block on a client joined to a server.
 *
 * <p>Such a client is told only that a block changed, and the game never removes the block entity
 * of the block that was there. When a chest later takes the place of a grug block, the chunk hands
 * the stale grug block entity to the chest, and {@code ChestBlock.onRemoved} fails with a
 * ClassCastException on the next explosion that reaches it, which stops the client (#163). The
 * block entity is checked against the block when it is asked for and dropped when they disagree, so
 * the game makes the right one.
 */
@GrugGenerated(
        "a mixin into the game's chunk code: the game runs it, and the dedicated-server job crashes"
                + " its client without it")
@Mixin(WorldChunk.class)
public abstract class StaleBlockEntityMixin {
    @Shadow
    public abstract int getBlockAt(int x, int y, int z);

    @Shadow
    public abstract void removeBlockEntityAt(int x, int y, int z);

    @Shadow
    public abstract BlockEntity getBlockEntityAt(int x, int y, int z);

    @Inject(method = "getBlockEntityAt", at = @At("RETURN"), cancellable = true)
    private void grug$dropStaleGrugBlockEntity(
            int x, int y, int z, CallbackInfoReturnable<BlockEntity> cir) {
        if (!(cir.getReturnValue() instanceof GrugBlockEntity)) {
            return;
        }
        if (!(Block.BY_ID[getBlockAt(x, y, z)] instanceof GrugBlock)) {
            removeBlockEntityAt(x, y, z);
            cir.setReturnValue(getBlockEntityAt(x, y, z));
        }
    }
}
