package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recomputes a joined client's light over the box a region packet wrote (#1740).
 *
 * <p>This version applies a region packet by copying every cell of the packet's box, blocks,
 * metadata, block light and sky light ({@code Chunk.loadFromPacket}), then rebuilding only the
 * chunk's height map. It queues no light update for the cells the copy changed and cancels none
 * that the height recompute queued, so a cell whose level the client had already got wrong keeps
 * it: the measured joined client held sky 9 against this server's 0 at {@code -25,80,-70} and sky
 * 15 against 14 at {@code -19,78,-64} (#1740).
 *
 * <p>The hook recomputes exactly the box the call wrote, from the call's own {@code x, y, z, sizeX,
 * sizeY, sizeZ} with the y range clamped the way the game clamps it, so every cell the copy touched
 * is recomputed from the blocks the client holds now, and so is every update still queued for it.
 * The loader's settle queues both fields of that box in pieces the engine accepts (a single update
 * above 32768 cells is skipped by {@code LightUpdate.updateLight}, and a whole 16x128x16 chunk is
 * exactly at the cap), drains the queue to the end, and sweeps again until a whole sweep changes
 * neither field. That is #1740's own answer for the client: drain the update a region application
 * leaves queued.
 *
 * <p>Only a joined client's copy is repaired: the world a client-only run edits is its server's,
 * which the room's build already settles, and {@code isRemote} is false there. The mixin is in this
 * loader's client list, so a dedicated server never loads it at all.
 */
@Mixin(World.class)
public abstract class ChunkDataRelightMixin {
    @Inject(method = "handleChunkDataUpdate", at = @At("RETURN"))
    private void grug$relightRegion(
            int x,
            int y,
            int z,
            int sizeX,
            int sizeY,
            int sizeZ,
            byte[] chunkData,
            CallbackInfo ci) {
        World world = (World) (Object) this;
        if (!world.isRemote) {
            return;
        }
        int minY = Math.max(y, 0);
        // 128 is every Beta world's height, and the end this call clamps its own y range to.
        int maxY = Math.min(y + sizeY, 128) - 1;
        if (maxY < minY) {
            return;
        }
        ModLoaderAdapter adapter = GrugCore.getAdapter();
        if (adapter != null) {
            adapter.relightRoom(world, x, minY, z, x + sizeX - 1, maxY, z + sizeZ - 1);
        }
    }
}
