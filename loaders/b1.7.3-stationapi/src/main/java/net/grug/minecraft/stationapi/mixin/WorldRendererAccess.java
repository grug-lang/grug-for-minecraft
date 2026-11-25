package net.grug.minecraft.stationapi.mixin;

import net.minecraft.client.render.WorldRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Exposes {@code WorldRenderer}'s pending chunk rebuilds, which {@code compileChunks} drains inside
 * the frame before the chunks are drawn.
 *
 * <p>The field is private, and this loader's game classes come from its own mappings rather than
 * from a patch this repository keeps, so an accessor is the one way to read it without reflecting
 * on a mapped name. See {@code StationApiAdapter.isWorldSettled} and #257.
 */
@Mixin(WorldRenderer.class)
public interface WorldRendererAccess {
    @Accessor("dirtyChunks")
    List<?> grug$dirtyChunks();

    /**
     * Marks every section a block box touches, and the sections beside it, for compiling again: the
     * game's own answer to "this region changed", which grows the box by one block before it marks.
     *
     * <p>A loader that writes light itself calls this for the box it relit, because the rebuild a
     * changed light needs is the renderer's and not the light engine's. See {@code
     * StationApiRoomRemeshClient#markSections} and #1580.
     */
    @Invoker("markDirty")
    void grug$markDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
}
