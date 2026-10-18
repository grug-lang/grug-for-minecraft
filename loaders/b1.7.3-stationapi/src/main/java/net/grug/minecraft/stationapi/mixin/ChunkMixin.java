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

/**
 * Creates a grug block entity's grug entity the moment the game registers it with a world and sets
 * its position, which is what 1.20.6's {@code onLoad} means. This version has no callback on the
 * block entity itself, so this reaches the two places the game assigns the world and the
 * coordinates: {@code setBlockEntity} for a block that is placed into a loaded chunk, and {@code
 * load} for one that comes back with its chunk. It mirrors the Ornithe loader's WorldChunkMixin,
 * whose own mappings name the same class {@code WorldChunk}.
 *
 * <p>The two are split because {@code setBlockEntity} also runs while a chunk is being read from
 * disk, before that chunk is in the world, and the grug entity cannot be created then: it resolves
 * its own block through the world, which would load the chunk again. {@code loaded} is false during
 * that read and true once the chunk is in the world, so the placement path is the one that inits,
 * and the chunk path inits at the end of {@code load}, by which point the chunk is reachable. A
 * chunk unload destroys the entity, so it does not outlive its block entity and is inited again on
 * the next load.
 */
@Mixin(Chunk.class)
public abstract class ChunkMixin {

    @Shadow public boolean loaded;

    @Shadow public Map<?, ?> blockEntities;

    @Inject(method = "setBlockEntity", at = @At("RETURN"))
    private void grug$initOnPlacement(
            int x, int y, int z, BlockEntity blockEntity, CallbackInfo ci) {
        if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            System.out.println("GRUG187 setBlockEntity loaded=" + loaded);
            if (loaded) {
                grugBlockEntity.onRegistered();
            }
        }
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void grug$initOnChunkLoad(CallbackInfo ci) {
        for (Object blockEntity : blockEntities.values()) {
            if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
                System.out.println("GRUG187 load");
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
