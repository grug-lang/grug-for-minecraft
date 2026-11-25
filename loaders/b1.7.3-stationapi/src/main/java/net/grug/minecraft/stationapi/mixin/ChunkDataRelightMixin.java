package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.core.GrugChunkRelight;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recomputes a joined client's light over the columns a region packet raised or lowered (#1740).
 *
 * <p>This version applies a region packet by copying its blocks and its light bytes ({@code
 * Chunk.loadFromPacket}) and recomputing the chunk's height map, with no light update queued for
 * the cells that recompute leaves stale. A packet that raised a column's height therefore leaves
 * the cell just above a room's ceiling at the sky's 15 that the open column had, while the server's
 * field there is the 14 its neighbours give it, and no later packet covers that cell: re-sending
 * the region three times changed nothing.
 *
 * <p>The chunk's heights are read before the packet is applied and compared after it, and every
 * column whose height moved hands {@link GrugChunkRelight} its old and new height. The box that
 * comes back is recomputed with the loader's own settle, so the field the client ends on is the one
 * its blocks define rather than the one the packet happened to carry. A chunk's first delivery
 * covers the whole column and already carries the server's field for every cell of it, so it is not
 * swept.
 *
 * <p>Only a joined client's copy is repaired: the world a client-only run edits is its server's,
 * which the room's build already settles, and {@code isRemote} is false there. The mixin is in this
 * loader's client list, so a dedicated server never loads it at all.
 */
@Mixin(World.class)
public abstract class ChunkDataRelightMixin {
    /** The chunk's heights before the packet, or null when this packet is not one to follow. */
    @Unique private int[] grug$heightsBefore;

    @Unique private int grug$chunkX;

    @Unique private int grug$chunkZ;

    @Inject(method = "handleChunkDataUpdate", at = @At("HEAD"))
    private void grug$holdHeights(
            int x,
            int y,
            int z,
            int sizeX,
            int sizeY,
            int sizeZ,
            byte[] chunkData,
            CallbackInfo ci) {
        grug$heightsBefore = null;
        World world = (World) (Object) this;
        if (!world.isRemote || GrugChunkRelight.isWholeColumn(y, sizeY)) {
            return;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        Chunk chunk = world.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        int columns = GrugChunkRelight.COLUMNS * GrugChunkRelight.COLUMNS;
        int[] before = new int[columns];
        for (int localZ = 0; localZ < GrugChunkRelight.COLUMNS; localZ++) {
            for (int localX = 0; localX < GrugChunkRelight.COLUMNS; localX++) {
                before[GrugChunkRelight.column(localX, localZ)] = chunk.getHeight(localX, localZ);
            }
        }
        grug$chunkX = chunkX;
        grug$chunkZ = chunkZ;
        grug$heightsBefore = before;
    }

    @Inject(method = "handleChunkDataUpdate", at = @At("RETURN"))
    private void grug$recomputeMovedColumns(
            int x,
            int y,
            int z,
            int sizeX,
            int sizeY,
            int sizeZ,
            byte[] chunkData,
            CallbackInfo ci) {
        int[] before = grug$heightsBefore;
        grug$heightsBefore = null;
        if (before == null) {
            return;
        }
        World world = (World) (Object) this;
        Chunk chunk = world.getChunk(grug$chunkX, grug$chunkZ);
        if (chunk == null) {
            return;
        }
        int[] after = new int[before.length];
        for (int localZ = 0; localZ < GrugChunkRelight.COLUMNS; localZ++) {
            for (int localX = 0; localX < GrugChunkRelight.COLUMNS; localX++) {
                after[GrugChunkRelight.column(localX, localZ)] = chunk.getHeight(localX, localZ);
            }
        }
        int[] box = GrugChunkRelight.box(before, after, grug$chunkX, grug$chunkZ);
        if (box == null) {
            return;
        }
        ModLoaderAdapter adapter = GrugCore.getAdapter();
        if (adapter != null) {
            adapter.relightRoom(world, box[0], box[1], box[2], box[3], box[4], box[5]);
        }
    }
}
