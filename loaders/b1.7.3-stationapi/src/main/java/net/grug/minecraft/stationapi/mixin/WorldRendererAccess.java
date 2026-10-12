package net.grug.minecraft.stationapi.mixin;

import net.minecraft.client.render.WorldRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

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
}
