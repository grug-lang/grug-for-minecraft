package net.grug.minecraft.forge.mixin;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a light engine's section storage, which {@code LightEngine} keeps protected.
 *
 * <p>The light a client's chunk mesh is baked from is the storage's {@code visibleSectionData},
 * which is swapped in on the render thread once a light packet's data has been applied, and the
 * engine offers no public way to reach it. {@link LayerLightSectionStorageAccessor} then reads the
 * layer itself. See #1266.
 *
 * <p>Read on both sides, because a dedicated server compares its own world's section with the one
 * its joined client answers, so this is a mixin of the common list rather than of the client one.
 */
@Mixin(LightEngine.class)
public interface LightEngineStorageAccessor {
    @Accessor("storage")
    LayerLightSectionStorage<?> grug$storage();
}
