package net.grug.minecraft.forge125.block.entity;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugPacketDirection;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.forge125.control.GrugPacketRegister;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.NetHandler;
import net.minecraft.src.Packet;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Sends a grug block entity's saved data to the client's copy of it (#1093), which is where {@link
 * net.grug.minecraft.core.GrugSavedDataSync} asks on 1.2.5.
 *
 * <p>The packet is grug's own because this version has none that carries data: {@code
 * Packet132TileEntityData} holds three ints and an action type, and its handler only hands those to
 * a sign, a mob spawner or a tile entity's own {@code onDataPacket}. The data is what the frame
 * pass reads, and not the block entity's inventory: the inventory has its own window packets, and a
 * client that holds an open GUI would have it reset by a copy of the server's.
 *
 * <p>Both ends register it under {@link #ID} ({@link #register}), because a packet id the receiver
 * does not know closes the connection.
 */
@GrugGenerated(
        "the game's packet type: its registration and its hand-off to the game's handlers need a"
                + " running game")
public class GrugSavedDataPacket extends Packet {
    /**
     * Vanilla and Forge use 0 to 26, 28 to 43, 50 to 54, 60, 61, 70, 71 and 100 to 108; 151 is
     * free.
     */
    public static final int ID = 151;

    private int x;
    private int y;
    private int z;

    /** The block the data belongs to, so a packet cannot write it into a different block. */
    private int blockId;

    private NBTTagCompound data = new NBTTagCompound();

    /**
     * Whether the client is also told to build the block's mesh again, which a chunk-mesh block
     * needs before it reads the data. A change to saved data is sent without it, so the client only
     * reads the data and no section is rebuilt; a block that draws per frame reads it on the next
     * frame.
     */
    private boolean redraw;

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugSavedDataPacket() {}

    /** The saved data of {@code blockEntity}, for the clients that hold its chunk. */
    public GrugSavedDataPacket(GrugBlockEntity blockEntity) {
        this(blockEntity, false);
    }

    /**
     * The saved data of {@code blockEntity}, and whether the client also has to build the block's
     * mesh again with it.
     */
    public GrugSavedDataPacket(GrugBlockEntity blockEntity, boolean redraw) {
        // The game queues a block change as chunk data, and this packet describes that block, so
        // it has to share the queue: a packet on the normal queue is drained first and arrives
        // before the block exists, and the handler below drops it for a different block at the
        // position. Vanilla's Packet53BlockChange sets the same field.
        this.isChunkDataPacket = true;
        this.x = blockEntity.xCoord;
        this.y = blockEntity.yCoord;
        this.z = blockEntity.zCoord;
        this.blockId =
                blockEntity.worldObj.getBlockId(
                        blockEntity.xCoord, blockEntity.yCoord, blockEntity.zCoord);
        this.redraw = redraw;
        blockEntity.writeSavedData(data);
    }

    /**
     * Registers the packet in the game's packet table. Both ends need it there, because an id the
     * receiver does not know closes the connection, but only the server may send it and only a
     * client applies it.
     */
    public static void register() {
        GrugPacketRegister.register(
                ID, GrugSavedDataPacket.class, GrugPacketDirection.SERVER_TO_CLIENT);
    }

    @Override
    public void readPacketData(DataInputStream in) throws IOException {
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        blockId = in.readInt();
        redraw = in.readBoolean();
        data = readNBTTagCompound(in);
    }

    @Override
    public void writePacketData(DataOutputStream out) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeInt(blockId);
        out.writeBoolean(redraw);
        writeNBTTagCompound(data, out);
    }

    /**
     * Applies the data to the client's copy of the block entity, which the frame pass reads from
     * the next frame on. A client that has not loaded the block's chunk yet has no copy to update,
     * and reads the data the chunk carries when it does. The block at the position has to be the
     * one the packet was sent for, or a packet that arrives after the block changed, which the next
     * test's fixture does, would write one block's data into another's.
     */
    @Override
    public void processPacket(NetHandler handler) {
        if (!GrugSavedDataSync.appliesOn(GrugCore.getAdapter().getSide())) {
            return;
        }
        Object level = GrugCore.getAdapter().getLevel();
        if (!(level instanceof World)) {
            return;
        }
        World world = (World) level;
        if (world.getBlockId(x, y, z) != blockId) {
            return;
        }
        TileEntity blockEntity = world.getBlockTileEntity(x, y, z);
        if (blockEntity instanceof GrugBlockEntity) {
            ((GrugBlockEntity) blockEntity).readSavedData(data);
            if (redraw) {
                // The client builds its own mesh again from the data it just read, which is the
                // local world's mark, not a packet: the data travels server to client only.
                GrugCore.getAdapter().redraw(blockEntity);
            }
        }
    }

    /**
     * The size the game's network queue counts this packet as. It is worked out from what is
     * written, because the data is a variable amount of NBT and the queue's limit decides when the
     * server sends the next chunk.
     */
    @Override
    public int getPacketSize() {
        ByteArrayOutputStream counted = new ByteArrayOutputStream();
        try {
            writePacketData(new DataOutputStream(counted));
        } catch (IOException e) {
            throw new IllegalStateException("cannot measure a saved data packet", e);
        }
        return counted.size();
    }
}
