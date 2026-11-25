package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugChunkRelight;
import net.grug.minecraft.ornithe.ClientRelight;
import net.minecraft.client.world.MultiplayerWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recomputes a joined client's light over the columns a region packet raised or lowered (#1740).
 *
 * <p>This version applies a region packet by copying its blocks and its light bytes ({@code
 * WorldChunk.unpackChunkData}) and recomputing the chunk's height map, with no light update queued
 * for the cells that recompute leaves stale. A packet that raised a column's height therefore
 * leaves the cell just above a room's ceiling at the sky's 15 that the open column had, while the
 * server's field there is the 14 its neighbours give it, and no later packet covers that cell:
 * re-sending the region three times changed nothing.
 *
 * <p>The chunk's heights are read before the packet is applied and compared after it, and every
 * column whose height moved hands {@link GrugChunkRelight} its old and new height. The box that
 * comes back is recomputed until both fields stop changing, so the client ends on the field its own
 * blocks define rather than the one the packet happened to carry. A chunk's first delivery covers
 * the whole column and already carries the server's field for every cell of it, so it is not swept.
 *
 * <p>Only a joined client's copy is repaired: {@link MultiplayerWorld} is that copy, while a
 * singleplayer client holds the one world its server edits, which the room's build already settles.
 * This mixin is in the client list of this loader's mixin configuration, so a dedicated server
 * never loads it.
 */
@Mixin(World.class)
public abstract class ChunkDataRelightMixin {
    /** The chunk's heights before the packet, or null when this packet is not one to follow. */
    @Unique private int[] grug$heightsBefore;

    @Unique private int grug$chunkX;

    @Unique private int grug$chunkZ;

    @Inject(method = "unpackChunkData", at = @At("HEAD"))
    private void grug$holdHeights(
            int minX,
            int minY,
            int minZ,
            int widthX,
            int height,
            int widthZ,
            byte[] data,
            CallbackInfo ci) {
        grug$heightsBefore = null;
        if (!(((Object) this) instanceof MultiplayerWorld)) {
            return;
        }
        World world = (World) (Object) this;
        if (GrugChunkRelight.isWholeColumn(minY, height)) {
            return;
        }
        int chunkX = minX >> 4;
        int chunkZ = minZ >> 4;
        WorldChunk chunk = world.getChunkAt(chunkX, chunkZ);
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

    @Inject(method = "unpackChunkData", at = @At("RETURN"))
    private void grug$recomputeMovedColumns(
            int minX,
            int minY,
            int minZ,
            int widthX,
            int height,
            int widthZ,
            byte[] data,
            CallbackInfo ci) {
        int[] before = grug$heightsBefore;
        grug$heightsBefore = null;
        if (before == null) {
            return;
        }
        World world = (World) (Object) this;
        WorldChunk chunk = world.getChunkAt(grug$chunkX, grug$chunkZ);
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
        if (box != null) {
            ClientRelight.relight(world, box);
        }
    }
}
