package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugStaleBlockEntity;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops a block entity that outlived its block on a client joined to a server (#1354).
 *
 * <p>Such a client is told only that a block changed, and the game never removes the block entity
 * of the block that was there. The chunk hands out what it holds before it looks at the block, so a
 * grug block placed where a chest was got the chest, and a block that has no entity at all kept
 * answering for the chest. The block entity is checked against the block when it is asked for and
 * dropped when they disagree, so the game makes the right one. Beta has the same mixin.
 */
@GrugGenerated(
        "a mixin into the game's chunk code: the game runs it, and the dedicated-server job reads a"
                + " stale entity without it")
@Mixin(WorldChunk.class)
public abstract class StaleBlockEntityMixin {
    @Shadow public World world;

    @Shadow
    public abstract int getBlockAt(int x, int y, int z);

    @Shadow
    public abstract void removeBlockEntityAt(int x, int y, int z);

    @Shadow
    public abstract BlockEntity getBlockEntityAt(int x, int y, int z);

    @Inject(method = "getBlockEntityAt", at = @At("RETURN"), cancellable = true)
    private void grug$dropStaleForeignBlockEntity(
            int x, int y, int z, CallbackInfoReturnable<BlockEntity> cir) {
        BlockEntity found = cir.getReturnValue();
        if (found == null) {
            return;
        }
        int id = getBlockAt(x, y, z);
        if (GrugStaleBlockEntity.shouldDropOnClient(
                found instanceof GrugBlockEntity,
                Block.BY_ID[id] instanceof GrugBlock,
                Block.HAS_BLOCK_ENTITY[id],
                world != null && world.isMultiplayer)) {
            removeBlockEntityAt(x, y, z);
            cir.setReturnValue(getBlockEntityAt(x, y, z));
        }
    }
}
