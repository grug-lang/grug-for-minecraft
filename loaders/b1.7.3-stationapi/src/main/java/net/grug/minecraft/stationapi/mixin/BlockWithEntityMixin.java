package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creates a grug block entity's grug entity when a grug block is placed, after {@code onPlaced} has
 * made the block entity and registered it.
 *
 * <p>This exists next to ChunkMixin because this version's client does not reach the chunk's
 * registration method for a block placed into a chunk it has not finished receiving, while {@code
 * onPlaced} always runs. Both paths call {@code onRegistered}, which creates the entity once, so
 * whichever the game reaches first is the moment.
 */
@Mixin(BlockWithEntity.class)
public abstract class BlockWithEntityMixin {

    @Inject(method = "onPlaced", at = @At("RETURN"))
    private void grug$initOnPlaced(World world, int x, int y, int z, CallbackInfo ci) {
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            grugBlockEntity.onRegistered();
        }
    }
}
