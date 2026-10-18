package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(Chunk.class)
public abstract class ChunkMixin {

    @Shadow public boolean loaded;

    @Shadow public Map<?, ?> blockEntities;

    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void grug$initOnPlacement(
            int x, int y, int z, BlockEntity blockEntity, CallbackInfo ci) {
        if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            org.apache.logging.log4j.LogManager.getLogger("grug")
                    .error("GRUG187 setBlockEntity loaded=" + loaded);
            if (loaded) {
                grugBlockEntity.onRegistered();
            }
        }
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void grug$initOnChunkLoad(CallbackInfo ci) {
        for (Object blockEntity : blockEntities.values()) {
            if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
                org.apache.logging.log4j.LogManager.getLogger("grug").error("GRUG187 load");
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
