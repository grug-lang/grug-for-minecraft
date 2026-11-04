package net.grug.minecraft.stationapi.block.entity;

import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.server.GrugServerRun;
import net.minecraft.block.Block;

/**
 * Sends a grug block entity's saved data to the joined clients on a b1.7.3 StationAPI dedicated
 * server, which is where {@link GrugSavedDataSync} asks.
 *
 * <p>A singleplayer game has no server and no second copy of the world, so it installs none: the
 * copy the script writes is the one the frame pass reads.
 */
public final class GrugSavedDataStation implements GrugSavedDataSync.Transport {

    @Override
    public boolean onServer(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        return entity.world != null && !entity.world.isRemote;
    }

    @Override
    public boolean frameOnly(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        if (entity.world == null) {
            return false;
        }
        Block block = Block.BLOCKS[entity.world.getBlockId(entity.x, entity.y, entity.z)];
        return block instanceof GrugBlock
                && ((GrugBlock) block).drawsCustomGeometry()
                && !((GrugBlock) block).drawsChunkGeometry();
    }

    @Override
    public void send(Object blockEntity) {
        GrugServerRun.sendToAllPlayers(new GrugSavedDataPacket((GrugBlockEntity) blockEntity));
    }
}
