package net.grug.minecraft.forge.mixin;

import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the section data a light engine publishes for rendering, which is volatile and protected.
 *
 * <p>{@code LightEngine.getDataLayerData} answers a section's queued layer before the visible one,
 * so a read taken between a light packet and the render thread's swap answers data no frame has
 * been meshed from yet. A caller that compares the copy the mesher reads has to read this field,
 * which is the one the swap writes and the one the mesher's per-block light read goes through.
 *
 * <p>Read on both sides, so this is a mixin of the common list rather than of the client one.
 */
@Mixin(LayerLightSectionStorage.class)
public interface LayerLightSectionStorageAccessor {
    @Accessor("visibleSectionData")
    DataLayerStorageMap<?> grug$visibleSectionData();
}
