package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.WorldChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {

    @Shadow public boolean loaded;

    @Inject(method = "setBlockEntityAt", at = @At("RETURN"))
    private void grug$initOnPlacement(
            int x, int y, int z, BlockEntity blockEntity, CallbackInfo ci) {
        if (loaded && blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            grugBlockEntity.onRegistered();
        }
    }
}
