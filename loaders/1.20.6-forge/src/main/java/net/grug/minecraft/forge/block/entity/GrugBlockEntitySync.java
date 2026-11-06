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
 * mesh is sent one by {@link #redraw}, which is what makes the client build that mesh again.
 */
public final class GrugBlockEntitySync implements GrugSavedDataSync.Transport {

    @GrugGenerated(
            "a script call only reaches a block entity in the server's level, so the no-level and"
                    + " client-side answers are defensive and no test can make them")
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
     * The game's own block update: on the server it sends the tracking players the block entity's
     * update packet, whose handler has the client build the block's mesh again because the data it
     * reads changed; on a client it marks the section for meshing, which is the local rebuild.
     */
    @Override
    public void redraw(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        entity.getLevel()
                .sendBlockUpdated(
                        entity.getBlockPos(),
                        entity.getBlockState(),
                        entity.getBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }
}
