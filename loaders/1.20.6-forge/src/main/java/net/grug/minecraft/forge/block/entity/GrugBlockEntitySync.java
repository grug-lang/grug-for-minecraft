package net.grug.minecraft.forge.block.entity;

import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.network.PacketDistributor;

/**
 * Sends a grug block entity's saved data to the client's copy of it on 1.20.6, which is where
 * {@link GrugSavedDataSync} asks. The client keeps a copy of the block entity, and the frame pass
 * reads the saved numbers from that copy, so a number the script saves on the server reaches the
 * frame only if it is sent.
 *
 * <p>The game has one block entity data packet, so what a client does with it is carried in its
 * tag: a change send carries the data alone and the client only reads it, and a redraw's packet
 * carries {@link GrugBlockEntity#REDRAW_TAG} and the client's own {@code onDataPacket} marks its
 * section and builds the block's mesh again from the data it now holds. A block whose geometry is
 * in the chunk's mesh is therefore sent by {@link #redraw} alone, and a change to saved data that
 * only its mesh draws waits for the script's {@code redraw}. A client's own redraw is the adapter's
 * local mark, not a packet: a client sends nothing.
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

    /**
     * Whether the block draws its own geometry, which is what makes the game ask it for {@code
     * render_frame}, and so what has to reach the client's copy on a change. The export id cannot
     * answer this: it resolves for every script, because {@code mod_api.json} declares {@code
     * render_frame}, and a check against it would have an arm no test can take. A block that keeps
     * its geometry in the chunk's mesh answers yes too.
     */
    @Override
    public boolean drawsFrame(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        GrugBlock block = (GrugBlock) entity.getBlockState().getBlock();
        return block.drawsCustomGeometry();
    }

    @Override
    public void send(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        broadcast(entity, entity.getUpdatePacket());
    }

    /**
     * The server sends the tracking players the data with {@link GrugBlockEntity#REDRAW_TAG} set,
     * and the client's own {@code GrugBlockEntity.onDataPacket} marks its section and builds the
     * block's mesh again. A server block update would not do that: the game broadcasts a block
     * update packet, and the client drops it when the state is the one it already holds. A client's
     * own redraw is the adapter's local mark, so this transport sends one packet per redraw and
     * never a block update.
     */
    @Override
    public void redraw(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        broadcast(entity, entity.getRedrawPacket());
    }

    /** Sends one block entity packet to the players tracking the chunk the entity is in. */
    private static void broadcast(GrugBlockEntity entity, Packet<?> packet) {
        ServerLevel serverLevel = (ServerLevel) entity.getLevel();
        LevelChunk chunk = serverLevel.getChunkAt(entity.getBlockPos());
        PacketDistributor.TRACKING_CHUNK.with(chunk).send(packet);
    }
}
