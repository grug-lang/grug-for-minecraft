package net.grug.minecraft.ornithe.mixin;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Notifies a world's listeners that the block at a position changed, which is what queues the
 * change in a dedicated server's player manager and so reaches a joined client.
 *
 * <p>{@code place_block} writes with {@code setBlockQuietly}, which only writes the chunk, and it
 * is {@code setBlock} that calls this afterwards. This invoker calls it without the neighbour
 * updates {@code setBlock} also adds. The named method is what the mixin's refmap maps to the
 * intermediary name the game runs, so the call resolves on the dedicated server, whose classes are
 * named in that namespace. See #1078.
 */
@Mixin(World.class)
public interface WorldNotifyAccess {
    @Invoker("notifyBlockChanged")
    void grug$notifyBlockChanged(int x, int y, int z);
}
