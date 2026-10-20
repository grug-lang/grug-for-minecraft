package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.WorldChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Map;

/**
 * Creates a grug block entity's grug entity the moment the game registers it with a world and sets
 * its position, which is what 1.20.6's {@code onLoad} means. Beta and Alpha have no callback on the
 * block entity itself, so this reaches the two places the game assigns the world and the
 * coordinates: {@code setBlockEntityAt} for a block that is placed into a loaded chunk, and {@code
 * load} for one that comes back with its chunk.
 *
 * <p>The two are split because {@code setBlockEntityAt} also runs while a chunk is being read from
 * disk, before that chunk is in the world, and the grug entity cannot be created then: it resolves
 * its own block through the world, which would load the chunk again. {@code loaded} is false during
 * that read and true once the chunk is in the world, so the placement path is the one that inits,
 * and the chunk path inits at the end of {@code load}, by which point the chunk is reachable.
 *
 * <p>A chunk unload destroys the entity, so it does not outlive the block entity it was created
 * for: Alpha's unload does not remove its block entities, so without this a chunk that unloads
 * leaves a grug entity pointing at a block entity the game has dropped, which the next hot reload
 * reads members from and crashes on.
 */
@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {

    @Shadow public boolean loaded;

    @Shadow public Map<?, ?> blockEntities;

    @Inject(method = "setBlockEntityAt", at = @At("RETURN"))
    private void grug$initOnPlacement(
            int x, int y, int z, BlockEntity blockEntity, CallbackInfo ci) {
        if (loaded && blockEntity instanceof GrugBlockEntity) {
            GrugBlockEntity grugBlockEntity = (GrugBlockEntity) blockEntity;
            grugBlockEntity.onRegistered();
        }
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void grug$initOnChunkLoad(CallbackInfo ci) {
        // A snapshot, because an init() that places a block in this chunk adds to the map.
        for (Object blockEntity : new ArrayList<>(blockEntities.values())) {
            if (blockEntity instanceof GrugBlockEntity) {
                GrugBlockEntity grugBlockEntity = (GrugBlockEntity) blockEntity;
                grugBlockEntity.onRegistered();
            }
        }
    }

    @Inject(method = "unload", at = @At("RETURN"))
    private void grug$destroyOnChunkUnload(CallbackInfo ci) {
        for (Object blockEntity : blockEntities.values()) {
            if (blockEntity instanceof GrugBlockEntity) {
                GrugBlockEntity grugBlockEntity = (GrugBlockEntity) blockEntity;
                grugBlockEntity.destroyGrugEntity();
            }
        }
    }
}
