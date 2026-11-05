package net.grug.minecraft.ornithe.block.entity;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSavedDataQueue;
import net.grug.minecraft.core.GrugSavedDataSync;
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
import java.util.ArrayList;
import java.util.List;

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
 * the receiver does not know closes the connection with "Bad packet id". Alpha registers it with no
 * direction set, so the registry cannot refuse a client's copy: the server refuses it here instead,
 * before the packet is queued, so a server never applies saved data and never grows the queue.
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

    /** The block the data belongs to, so a packet cannot write it into a different block. */
    private int blockId;

    private NbtCompound data = new NbtCompound();

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugSavedDataPacket() {}

    /** The saved data of {@code blockEntity}, for the clients that hold its chunk. */
    public GrugSavedDataPacket(GrugBlockEntity blockEntity) {
        this.x = blockEntity.x;
        this.y = blockEntity.y;
        this.z = blockEntity.z;
        this.blockId = blockEntity.world.getBlock(blockEntity.x, blockEntity.y, blockEntity.z);
        blockEntity.writeSavedData(data);
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        blockId = in.readInt();
        data = NbtIo.read(in);
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeInt(blockId);
        NbtIo.write(data, out);
    }

    /**
     * Holds the data until the client's own thread applies it. Alpha hands a packet to the network
     * thread, and that thread's world lookup answers a different block entity at the same position
     * than the one the client's frame pass draws, so a write from here never reaches the frame.
     * {@link #applyPending} is called once a client tick with the world being drawn, on the thread
     * that draws.
     */
    private static final GrugSavedDataQueue<GrugSavedDataPacket> PENDING =
            new GrugSavedDataQueue<>();

    /** How many client ticks a packet waits for its block entity to appear. */
    private static final int PENDING_TICKS = 20;

    /** The ticks left to wait, and 0 for a packet being applied now. */
    private int waits;

    @Override
    public void handle(PacketHandler handler) {
        if (!GrugSavedDataSync.appliesOn(GrugCore.getAdapter().getSide())) {
            return;
        }
        waits = PENDING_TICKS;
        PENDING.add(this);
    }

    /**
     * Applies the data received since the last tick to the client's copy of the block entity, which
     * the frame pass reads from the next frame on, and is what {@link #handle} used to do on
     * whatever thread the network used.
     *
     * <p>A client that has not loaded the block's chunk yet has no copy to update, and the retry
     * does not cover it: an unloaded chunk reads block 0, so {@link #apply} answers 0 and drops the
     * packet, and the chunk the client loads instead is written from the server's copy, which
     * already carries the data. The retry covers a loaded chunk whose block entity has not reached
     * the client yet, which is what the next few ticks settle. The block at the position has to be
     * the one the packet was sent for, or a packet that arrives after the block changed, which the
     * next test's fixture does, would write one block's data into another's.
     *
     * <p>The queue is scoped to the world it was filled in, so a packet that is still waiting when
     * the client leaves that world is dropped rather than applied to whatever block the next world
     * holds at the same position.
     */
    public static void applyPending(Object level) {
        List<GrugSavedDataPacket> ready = PENDING.take(level);
        if (!(level instanceof World)) {
            return;
        }
        World world = (World) level;
        List<GrugSavedDataPacket> retry = new ArrayList<>();
        for (GrugSavedDataPacket packet : ready) {
            if (packet.apply(world) > 0) {
                retry.add(packet);
            }
        }
        // In front of the queue, not at its tail: the network thread queues a newer packet for the
        // same block while this pass runs, and the older retry must still apply before it.
        if (!retry.isEmpty()) {
            PENDING.requeue(retry);
        }
    }

    /** Applies this packet to {@code world}, and answers the ticks it still has to wait, or 0. */
    private int apply(World world) {
        if (world.getBlock(x, y, z) != blockId) {
            return 0;
        }
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        if (blockEntity instanceof GrugBlockEntity) {
            ((GrugBlockEntity) blockEntity).readSavedData(data);
            return 0;
        }
        waits = waits - 1;
        return Math.max(waits, 0);
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
