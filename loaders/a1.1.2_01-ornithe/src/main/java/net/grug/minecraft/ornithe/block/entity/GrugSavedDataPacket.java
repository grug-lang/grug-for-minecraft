package net.grug.minecraft.ornithe.block.entity;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.world.World;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Sends a grug block entity's saved data to the client's copy of it on Alpha (#1093), which is
 * where {@link net.grug.minecraft.core.GrugSavedDataSync} asks.
 *
 * <p>Alpha has no packet that carries a block entity's data, so grug has one of its own. The data
 * is what the frame pass reads, and not the block entity's inventory: the inventory has its own
 * window packets, and a client that holds an open GUI would have it reset by a copy of the
 * server's.
 *
 * <p>Both ends register it under {@link #ID} (see {@code PacketRegisterMixin}), because a packet id
 * the receiver does not know closes the connection with "Bad packet id".
 */
@GrugGenerated(
        "the game's packet type: its registration and its hand-off to the game's handlers need a"
                + " running game")
public class GrugSavedDataPacket extends Packet {
    /** Vanilla uses 0 to 6, 10 to 34, 50 to 53, 59 and 255; 151 is free. */
    public static final int ID = 151;

    private int x;
    private int y;
    private int z;
    private NbtCompound data = new NbtCompound();

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugSavedDataPacket() {}

    /** The saved data of {@code blockEntity}, for the clients that hold its chunk. */
    public GrugSavedDataPacket(GrugBlockEntity blockEntity) {
        this.x = blockEntity.x;
        this.y = blockEntity.y;
        this.z = blockEntity.z;
        blockEntity.writeSavedData(data);
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        data = NbtIo.read(in);
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        NbtIo.write(data, out);
    }

    /**
     * Applies the data to the client's copy of the block entity, which the frame pass reads from
     * the next frame on. A client that has not loaded the block's chunk yet has no copy to update,
     * and reads the data the chunk carries when it does.
     */
    @Override
    public void handle(PacketHandler handler) {
        Object level = GrugCore.getAdapter().getLevel();
        System.out.println(
                "[GRUG SYNC] alpha client apply at "
                        + x
                        + ","
                        + y
                        + ","
                        + z
                        + " level="
                        + level
                        + " data="
                        + data);
        if (!(level instanceof World)) {
            return;
        }
        BlockEntity blockEntity = ((World) level).getBlockEntity(x, y, z);
        if (blockEntity instanceof GrugBlockEntity) {
            ((GrugBlockEntity) blockEntity).readSavedData(data);
            System.out.println(
                    "[GRUG SYNC] alpha client applied, marker="
                            + net.grug.minecraft.grug.GrugSavedNumbers.get(blockEntity, "marker"));
        } else {
            System.out.println(
                    "[GRUG SYNC] alpha client has no grug block entity there: " + blockEntity);
        }
    }

    /**
     * The size the game's network queue counts this packet as. It is worked out from what is
     * written, because the data is a variable amount of NBT and the queue's limit decides when the
     * server sends the next chunk.
     */
    @Override
    public int getSize() {
        ByteArrayOutputStream counted = new ByteArrayOutputStream();
        try {
            write(new DataOutputStream(counted));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return counted.size();
    }
}
