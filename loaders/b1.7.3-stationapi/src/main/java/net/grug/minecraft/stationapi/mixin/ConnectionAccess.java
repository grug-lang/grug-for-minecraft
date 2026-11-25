package net.grug.minecraft.stationapi.mixin;

import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What a connection has queued and its write thread has not written, which is what core's {@code
 * GrugSendBuffer} paces a dedicated server's world on (#1802).
 *
 * <p>The count is private, and it is the game's own: the write thread subtracts from it as it
 * writes, and the game closes the player once it passes 1 MiB, so pacing on a second count of the
 * connection's queues could disagree with the check that drops the player.
 *
 * <p>{@code sendQueueSize} is the name this layer compiles against, and the mixin's refmap maps it
 * to the name the installed jar runs. This is the copy of the Ornithe loaders' accessor: the two
 * families share no source tree, only the game's shape.
 */
@Mixin(Connection.class)
public interface ConnectionAccess {
    @Accessor("sendQueueSize")
    int grug$sendQueueSize();
}
