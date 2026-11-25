package net.grug.minecraft.ornithe;

import net.grug.minecraft.ornithe.mixin.ConnectionAccess;
import net.minecraft.network.Connection;

/**
 * What the joined client's connection still holds unwritten, which is what core's {@code
 * GrugSendBuffer} paces the world on (#1802).
 *
 * <p>Alpha and Beta keep the count in a private field of {@code Connection}, read through {@link
 * ConnectionAccess} rather than recomputed from the queues the connection holds, because the game's
 * own count is the one its overflow check compares against.
 */
public final class GrugSendQueue {
    private GrugSendQueue() {}

    /** What the connection holds unwritten in bytes, which is 0 with no joined client yet. */
    public static int queued(Connection connection) {
        return connection == null ? 0 : ((ConnectionAccess) connection).grug$sendQueueSize();
    }
}
