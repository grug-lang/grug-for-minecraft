package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.Chunk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Chunk.class)
public abstract class ChunkMixin {

    private static final Logger LOGGER = LogManager.getLogger("GRUG187");

    @Shadow public boolean loaded;

    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void grug$initOnPlacement(
            int x, int y, int z, BlockEntity blockEntity, CallbackInfo ci) {
        if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            LOGGER.error("GRUG187 setBlockEntity loaded=" + loaded);
            if (loaded) {
                grugBlockEntity.onRegistered();
            }
        }
    }
}
