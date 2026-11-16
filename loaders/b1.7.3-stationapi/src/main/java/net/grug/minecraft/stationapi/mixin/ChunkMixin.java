package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.core.GrugStaleBlockEntity;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Map;

/**
 * Creates a grug block entity's grug entity when the game registers it with a world and sets its
 * position, and frees it again when the chunk unloads. This mirrors the Ornithe loader's
 * WorldChunkMixin, whose own mappings name this class {@code WorldChunk}.
 *
 * <p>{@code setBlockEntity} also runs while a chunk is being read from disk, before that chunk is
 * in the world, and the grug entity cannot be created then: it resolves its own block through the
 * world, which would load the chunk again. {@code loaded} is false during that read and true once
 * the chunk is in the world, so the placement path is the one that inits, and the chunk path inits
 * at the end of {@code load}, by which point the chunk is reachable. Block placement also meets
 * BlockWithEntityMixin, because this version's client reaches {@code onPlaced} for a block whose
 * chunk has not reached this method yet.
 */
@Mixin(Chunk.class)
public abstract class ChunkMixin {

    @Shadow public boolean loaded;

    @Shadow public Map<?, ?> blockEntities;

    @Shadow public World world;

    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void grug$initOnPlacement(
            int x, int y, int z, BlockEntity blockEntity, CallbackInfo ci) {
        if (loaded && blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            grugBlockEntity.onRegistered();
        }
    }

    /**
     * Drops a block entity that outlived its block on a joined client (#1354). The chunk hands out
     * what it holds before it checks the block, so a grug block placed where a chest was got the
     * chest, {@code GrugBoxRenderer} drew the placeholder cube for it, and a pipe saw an inventory
     * beside it that was no longer there. Dropped here, the game makes the block's own entity.
     */
    @Inject(method = "getBlockEntity", at = @At("RETURN"), cancellable = true)
    private void grug$dropStaleForeignBlockEntity(
            int x, int y, int z, CallbackInfoReturnable<BlockEntity> cir) {
        BlockEntity found = cir.getReturnValue();
        if (found == null || world == null) return;
        Chunk self = (Chunk) (Object) this;
        int id = self.getBlockId(x, y, z);
        if (id < 0 || id >= Block.BLOCKS.length || id >= Block.BLOCKS_WITH_ENTITY.length) return;
        if (GrugStaleBlockEntity.shouldDropOnClient(
                found instanceof GrugBlockEntity,
                Block.BLOCKS[id] instanceof GrugBlock,
                Block.BLOCKS_WITH_ENTITY[id],
                world.isRemote)) {
            // removeBlockEntityAt frees the entity through markRemoved only when this chunk is
            // loaded, so it is freed here whatever the chunk's state is (a second free is a no-op).
            if (found instanceof GrugBlockEntity) ((GrugBlockEntity) found).destroyGrugEntity();
            self.removeBlockEntityAt(x, y, z);
            cir.setReturnValue(self.getBlockEntity(x, y, z));
        }
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void grug$initOnChunkLoad(CallbackInfo ci) {
        // A snapshot, because an init() that places a block in this chunk adds to the map.
        for (Object blockEntity : new ArrayList<>(blockEntities.values())) {
            if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
                grugBlockEntity.onRegistered();
            }
        }
    }

    @Inject(method = "unload", at = @At("RETURN"))
    private void grug$destroyOnChunkUnload(CallbackInfo ci) {
        for (Object blockEntity : blockEntities.values()) {
            if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
                grugBlockEntity.destroyGrugEntity();
            }
        }
    }
}
