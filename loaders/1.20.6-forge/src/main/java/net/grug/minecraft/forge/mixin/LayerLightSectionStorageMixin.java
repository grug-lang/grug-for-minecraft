package net.grug.minecraft.forge.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.grug.minecraft.core.GrugLightSections;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-marks the client's applied light sections and the neighbours their meshes read dirty when the
 * light engine swaps the section data a light packet queued into the data the renderer reads
 * (#1179, #1207).
 *
 * <p>The client's light packet handler queues a section's bytes and marks the section and its 26
 * neighbours dirty in the same step, before the light engine has swapped them into the visible
 * data. If that frame builds a section's mesh before the swap, the mesh holds the older light, and
 * the swap marks nothing: {@code ClientChunkCache.onLightUpdate}, the client's only hook from the
 * engine to the renderer, runs for the sections whose stored levels changed, not for the queued
 * packet data. Adding each applied section to the affected set makes the swap mark it after the
 * visible data is in place, so the next compile reads the new light, as it does after a block
 * update. A block update marks the section again, which is why a saved number's redraw has hidden
 * this.
 *
 * <p>The neighbour is the second half of the same defect. A section's boundary mesh reads the
 * changed section's light live through the compile's region, so a neighbour laid out from the old
 * light keeps that light until its next block update, redraw or chunk reload marks it, even though
 * the light comparison passes once the data is final. Marking all 26 neighbours of every changed
 * section closed the gap but rebuilt sections whose mesh could not change, and the extra rebuilds
 * made {@code world_settled_beyond_view} run past its tick budget on the client-only and installed
 * jobs (#1209). What a neighbour's mesh can read is only the light of the blocks that touch the
 * section boundary: for each block the swap actually changed, {@link GrugLightSections} gives the
 * sections that block can reach, the section itself and the sections across the face, edge or
 * corner boundary it lies on. The diff below applies that to the section's visible and updating
 * layers, so a change in the middle of a section marks only that section and a change at a corner
 * marks the eight sections around it.
 *
 * <p>The guard leaves the server's own light storage, which runs in this process in singleplayer,
 * on its existing path; only the joined client's {@link ClientChunkCache} needs the extra mark.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin {

    @Shadow @Final protected LightChunkGetter chunkSource;

    @Shadow protected LongSet changedSections;

    @Shadow protected LongSet sectionsAffectedByLightUpdates;

    @Shadow protected DataLayerStorageMap<?> visibleSectionData;

    @Shadow protected DataLayerStorageMap<?> updatingSectionData;

    @Inject(method = "swapSectionMap", at = @At("HEAD"))
    private void grug$markAppliedSectionsDirty(CallbackInfo ci) {
        if (!(this.chunkSource instanceof ClientChunkCache)) {
            return;
        }
        for (long section : this.changedSections) {
            int sectionX = SectionPos.x(section);
            int sectionY = SectionPos.y(section);
            int sectionZ = SectionPos.z(section);
            DataLayer visible = this.visibleSectionData.getLayer(section);
            DataLayer updating = this.updatingSectionData.getLayer(section);
            boolean markedBlock = false;
            if (visible != updating) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int before = visible == null ? 0 : visible.get(x, y, z);
                            int after = updating == null ? 0 : updating.get(x, y, z);
                            if (before == after) {
                                continue;
                            }
                            markedBlock = true;
                            int offsets = GrugLightSections.maskForBlock(x, y, z);
                            while (offsets != 0) {
                                int offset = Integer.numberOfTrailingZeros(offsets);
                                offsets &= offsets - 1;
                                this.sectionsAffectedByLightUpdates.add(
                                        SectionPos.asLong(
                                                sectionX + GrugLightSections.offsetX(offset),
                                                sectionY + GrugLightSections.offsetY(offset),
                                                sectionZ + GrugLightSections.offsetZ(offset)));
                            }
                        }
                    }
                }
            }
            if (!markedBlock) {
                // A section whose stored light changed without a block this diff can see, such as
                // a removal or a layer the engine updated in place, is re-marked as itself, which
                // is the mark this mixin made before the neighbour half existed.
                this.sectionsAffectedByLightUpdates.add(section);
            }
        }
    }
}
