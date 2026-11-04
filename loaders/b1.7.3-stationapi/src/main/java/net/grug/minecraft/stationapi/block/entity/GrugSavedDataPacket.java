package net.grug.minecraft.stationapi.block.entity;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.NetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.world.World;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Sends a grug block entity's saved data to the client's copy of it on StationAPI (#1093), which is
 * where {@link net.grug.minecraft.core.GrugSavedDataSync} asks.
 *
 * <p>This version has no packet that carries a block entity's data, so grug has one of its own. The
 * data is what the frame pass reads, and not the block entity's inventory: the inventory has its
 * own window packets, and a client that holds an open GUI would have it reset by a copy of the
 * server's.
 *
 * <p>Both ends register it under {@link #ID} (see {@code InitListener}), because a packet id the
 * receiver does not know closes the connection with "Bad packet id".
 */
@GrugGenerated(
        "the game's packet type: its registration and its hand-off to the game's handlers need a"
                + " running game")
public class GrugSavedDataPacket extends Packet {
    /** Vanilla uses 0 to 6, 10 to 34, 50 to 53, 59, 100 to 108, 130, 255 and more; 151 is free. */
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
    public void read(DataInputStream in) {
        // The mapped signature declares no IOException; the connection's reader catches any
        // Exception and closes the connection, so a wrapped one ends it the same way.
        try {
            x = in.readInt();
            y = in.readInt();
            z = in.readInt();
            data = NbtIo.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(DataOutputStream out) {
        try {
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            NbtIo.write(data, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Applies the data to the client's copy of the block entity, which the frame pass reads from
     * the next frame on. A client that has not loaded the block's chunk yet has no copy to update,
     * and reads the data the chunk carries when it does.
     */
    @Override
    public void apply(NetworkHandler handler) {
        Object level = GrugCore.getAdapter().getLevel();
        if (!(level instanceof World)) {
            return;
        }
        BlockEntity blockEntity = ((World) level).getBlockEntity(x, y, z);
        if (blockEntity instanceof GrugBlockEntity) {
            ((GrugBlockEntity) blockEntity).readSavedData(data);
        }
    }

    /**
     * The size the game's network queue counts this packet as. It is worked out from what is
     * written, because the data is a variable amount of NBT and the queue's limit decides when the
     * server sends the next chunk.
     */
    @Override
    public int size() {
        ByteArrayOutputStream counted = new ByteArrayOutputStream();
        write(new DataOutputStream(counted));
        return counted.size();
    }
}
