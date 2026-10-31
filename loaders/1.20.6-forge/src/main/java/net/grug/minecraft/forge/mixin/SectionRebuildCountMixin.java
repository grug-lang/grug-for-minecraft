package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.core.GrugTestCounters;
import net.minecraft.client.renderer.SectionBufferBuilderPack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts the section meshes the client rebuilds, which #704 measures per test.
 *
 * <p>Experiment only: this mixin is on the throwaway branch that counts rebuilds, and is deleted
 * with it.
 */
@Mixin(
        targets =
                "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public class SectionRebuildCountMixin {

    @Inject(method = "doTask", at = @At("HEAD"))
    private void grug$countRebuild(SectionBufferBuilderPack pack, CallbackInfoReturnable<?> cir) {
        GrugTestCounters.sectionRebuilds.incrementAndGet();
    }
}
