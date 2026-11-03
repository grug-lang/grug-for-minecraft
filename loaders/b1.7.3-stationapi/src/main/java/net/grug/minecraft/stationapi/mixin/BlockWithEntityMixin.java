package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

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
    /**
     * The positions this thread is inside {@link #grug$initOnPlaced} for. A joined client builds a
     * block entity the server never sent when something asks the chunk for it, by calling {@code
     * onPlaced}, and a chunk that has not finished arriving does not keep what that makes, so the
     * lookup below asked the chunk again for the same position and recursed until the stack ended
     * (#1040). Only that same position, in the same world, is skipped: a grug block that {@code
     * onRegistered} places elsewhere is initialized as usual.
     */
    private static final ThreadLocal<List<Object[]>> grug$initializing =
            ThreadLocal.withInitial(ArrayList::new);

    @Inject(method = "onPlaced", at = @At("RETURN"))
    private void grug$initOnPlaced(World world, int x, int y, int z, CallbackInfo ci) {
        List<Object[]> active = grug$initializing.get();
        for (Object[] open : active) {
            if (open[0] == world
                    && (int) open[1] == x
                    && (int) open[2] == y
                    && (int) open[3] == z) {
                return;
            }
        }
        Object[] key = {world, x, y, z};
        active.add(key);
        try {
            BlockEntity blockEntity = world.getBlockEntity(x, y, z);
            if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
                grugBlockEntity.onRegistered();
            }
        } finally {
            active.remove(key);
            if (active.isEmpty()) {
                grug$initializing.remove();
            }
        }
    }
}
