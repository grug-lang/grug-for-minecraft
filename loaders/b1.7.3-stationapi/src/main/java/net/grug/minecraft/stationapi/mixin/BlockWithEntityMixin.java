package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockWithEntity.class)
public abstract class BlockWithEntityMixin {

    @Inject(method = "onPlaced", at = @At("RETURN"))
    private void grug$initOnPlaced(World world, int x, int y, int z, CallbackInfo ci) {
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        org.apache.logging.log4j.LogManager.getLogger("grug")
                .error("GRUG187 onPlaced be=" + blockEntity);
        if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            grugBlockEntity.onRegistered();
        }
    }
}
