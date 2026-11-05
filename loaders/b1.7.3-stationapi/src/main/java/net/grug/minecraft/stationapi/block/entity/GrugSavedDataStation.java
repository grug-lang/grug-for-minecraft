package net.grug.minecraft.stationapi.block.entity;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;
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
@GrugGenerated(
        "only the dedicated-server entrypoint from #163 installs this transport, and the coverage"
                + " gate counts the pull request's client runs, not the dispatch-only server legs")
public final class GrugSavedDataStation implements GrugSavedDataSync.Transport {

    @Override
    public boolean onServer(Object blockEntity) {
        return GrugCore.getAdapter().getSide() == GrugSide.DEDICATED_SERVER;
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
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        GrugServerRun.sendToWorld(entity.world, new GrugSavedDataPacket(entity));
    }
}
