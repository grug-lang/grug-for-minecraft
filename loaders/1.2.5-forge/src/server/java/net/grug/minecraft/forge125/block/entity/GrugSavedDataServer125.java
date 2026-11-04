package net.grug.minecraft.forge125.block.entity;

import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.forge125.GrugServerRun;
import net.grug.minecraft.forge125.block.GrugBlock;
import net.minecraft.src.Block;

/**
 * Sends a grug block entity's saved data to the joined clients on a 1.2.5 dedicated server, which
 * is where {@link GrugSavedDataSync} asks.
 *
 * <p>This half lives in the server's own sources because the player list is in classes a client
 * never loads. A singleplayer 1.2.5 game has no server and no second copy of the world, so it
 * installs none: the copy the script writes is the one the frame pass reads.
 */
public final class GrugSavedDataServer125 implements GrugSavedDataSync.Transport {

    @Override
    public boolean onServer(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        return entity.worldObj != null && !entity.worldObj.isRemote;
    }

    @Override
    public boolean frameOnly(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        if (entity.worldObj == null) {
            return false;
        }
        Block block =
                Block.blocksList[
                        entity.worldObj.getBlockId(entity.xCoord, entity.yCoord, entity.zCoord)];
        return block instanceof GrugBlock
                && ((GrugBlock) block).drawsCustomGeometry()
                && !((GrugBlock) block).drawsChunkGeometry();
    }

    @Override
    public void send(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        GrugServerRun.sendToWorld(entity.worldObj, new GrugSavedDataPacket(entity));
    }
}
