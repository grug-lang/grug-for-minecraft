package net.grug.minecraft.forge.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-marks the client's applied light sections dirty when the light engine swaps the section data a
 * light packet queued into the data the renderer reads (#1179).
 *
 * <p>The client's light packet handler queues a section's bytes and marks the section and its 26
 * neighbours dirty in the same step, before the light engine has swapped them into the visible
 * data. If that frame builds the section's mesh before the swap, the mesh holds the older light,
 * and the swap marks nothing: {@code ClientChunkCache.onLightUpdate}, the client's only hook from
 * the engine to the renderer, runs for the sections whose stored levels changed, not for the queued
 * packet data. Adding each applied section to the affected set makes the swap mark it after the
 * visible data is in place, so the next compile reads the new light, as it does after a block
 * update. A block update marks the section again, which is why a saved number's redraw has hidden
 * this.
 *
 * <p>Known follow-up, not fixed here: a neighbour's boundary mesh reads the changed section's light
 * live through the compile's region, so the neighbour can keep the old light until its next block
 * update, a redraw or a chunk reload marks it. That gap is narrower and the 26-neighbour expansion
 * that closed it left {@code world_settled_beyond_view} failing on the client-only and installed
 * jobs, so it is tracked separately rather than claimed here.
 *
 * <p>The guard leaves the server's own light storage, which runs in this process in singleplayer,
 * on its existing path; only the joined client's {@link ClientChunkCache} needs the extra mark.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin {

    @Shadow @Final protected LightChunkGetter chunkSource;

    @Shadow protected LongSet changedSections;

    @Shadow protected LongSet sectionsAffectedByLightUpdates;

    @Inject(method = "swapSectionMap", at = @At("HEAD"))
    private void grug$markAppliedSectionsDirty(CallbackInfo ci) {
        if (!(this.chunkSource instanceof ClientChunkCache)) {
            return;
        }
        this.sectionsAffectedByLightUpdates.addAll(this.changedSections);
    }
}
