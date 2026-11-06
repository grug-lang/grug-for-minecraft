package net.grug.minecraft.forge.block.entity;

import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraftforge.network.PacketDistributor;

/**
 * Sends a grug block entity's saved data to the client's copy of it on 1.20.6, which is where
 * {@link GrugSavedDataSync} asks. The client keeps a copy of the block entity, and the frame pass
 * reads the saved numbers from that copy, so a number the script saves on the server reaches the
 * frame only if it is sent.
 *
 * <p>The packet is the game's own block entity data packet, sent straight to the players tracking
 * the chunk. A block update is not sent with it: the number changes no block, and a block that
 * draws per frame reads the data from the next frame on. A block whose geometry is in the chunk's
 * mesh is sent the packet by {@link #redraw}, and the client's own {@code onDataPacket} marks its
 * section and builds that mesh again. A client's own redraw is the adapter's local mark, not a
 * packet: a client sends nothing.
 */
public final class GrugBlockEntitySync implements GrugSavedDataSync.Transport {

    @GrugGenerated(
            "a block entity with no level is defensive: a script call resolves one that is in a"
                    + " level, and no test can make one without")
    @Override
    public boolean onServer(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        return entity.getLevel() != null && !entity.getLevel().isClientSide();
    }

    @Override
    public boolean frameOnly(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        GrugBlock block = (GrugBlock) entity.getBlockState().getBlock();
        return block.drawsCustomGeometry() && !block.drawsChunkGeometry();
    }

    @Override
    public void send(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        net.minecraft.server.level.ServerLevel serverLevel =
                (net.minecraft.server.level.ServerLevel) entity.getLevel();
        net.minecraft.world.level.chunk.LevelChunk chunk =
                serverLevel.getChunkAt(entity.getBlockPos());
        PacketDistributor.TRACKING_CHUNK.with(chunk).send(entity.getUpdatePacket());
    }

    /**
     * The server sends the tracking players the data, and the client's own {@code
     * GrugBlockEntity.onDataPacket} marks its section and builds the block's mesh again. A server
     * block update would not do that: the game broadcasts a block update packet, and the client
     * drops it when the state is the one it already holds. A client's own redraw is the adapter's
     * local mark, so this transport sends one packet per redraw and never a block update.
     */
    @Override
    public void redraw(Object blockEntity) {
        send((GrugBlockEntity) blockEntity);
    }
}
