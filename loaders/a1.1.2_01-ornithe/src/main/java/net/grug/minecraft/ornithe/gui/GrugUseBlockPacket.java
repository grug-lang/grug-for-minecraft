package net.grug.minecraft.ornithe.gui;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Asks the joined client to right-click the block at a position in its own copy of the world,
 * because in Alpha the screen a container block opens is the client's alone (#1494).
 *
 * <p>The a1 server's {@code TestTickContext.use_block} makes the click in its own world, the world
 * the test reads, but this generation's block activation calls {@code PlayerEntity.openChestMenu},
 * which a joined client overrides to open the game's own chest screen and which sends a server
 * player nothing: Alpha has no container packet at all. A real Alpha client therefore opened the
 * chest itself, as the half of its right-click that {@code MultiplayerInteractionManager.useBlock}
 * made after sending the use packet, and only told the server that the click happened. This packet
 * is that half, for a test whose click starts on the server.
 *
 * <p>Both ends register it under {@link #ID}, because Alpha's registry refuses an id the receiver
 * does not know, but only a server sends it and only a client applies it.
 *
 * <p>The packet is delayed, like the block updates and block entity updates a test's own click may
 * depend on ({@code BlockUpdatePacket}, {@code BlocksUpdatePacket}, {@code
 * BlockEntityUpdatePacket}). {@code Connection.write} drains the normal send queue before the
 * delayed one, so a click in the normal queue can overtake the chest a test placed in the same
 * tick, and the client then reads air and drops the click. Sharing the delayed queue keeps the
 * order the server sent them in, and the server flushes its queued block changes before it sends
 * this (#1494).
 */
@GrugGenerated(
        "the game's packet type: only the dispatch-only multiplayer job opens a screen on a server")
public class GrugUseBlockPacket extends Packet {
    /**
     * The next free id after the four GUI packets, which take 152 to 155. 100 is the control
     * channel and 151 saved data; 156 is free here because the Beta GUI packets live in the b1
     * registry, a separate game.
     */
    public static final int ID = 156;

    /** The client half, which only the a1 client entrypoint installs (#1494). */
    public interface Handler {
        void useBlock(int x, int y, int z);
    }

    public static volatile Handler clientHandler = null;

    private int x;
    private int y;
    private int z;

    /** Empty, for the game's packet table, which makes one of each packet to read it into. */
    public GrugUseBlockPacket() {
        this.shouldDelay = true;
    }

    public GrugUseBlockPacket(int x, int y, int z) {
        this.shouldDelay = true;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void read(DataInputStream in) throws IOException {
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
    }

    @Override
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
    }

    /**
     * Hands the position to the client that installed a handler. A dedicated server installs none:
     * it is the side that sends this, and a click it would run is one it already ran in its own
     * world.
     */
    @Override
    public void handle(PacketHandler handler) {
        Handler target = clientHandler;
        if (target != null) {
            target.useBlock(x, y, z);
        }
    }

    @Override
    public int getSize() {
        return 12;
    }
}
