package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.renderer.SectionBufferBuilderPool;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the section buffer pool's size, which is how the settle answer sees a chunk compile that
 * a worker is still running.
 *
 * <p>{@code SectionRenderDispatcher.isQueueEmpty}, the render-queue check the settle answer used to
 * rest on alone, counts queued and finished compiles: a compile a worker is running has been taken
 * off the queues and holds one of these buffers, so it is invisible there. The pool's free count is
 * the only public signal for it, and the size it has to be compared against is private to the pool,
 * so it is read here at construction, while the pool is still full, and handed to the loader. See
 * #259.
 */
@Mixin(SectionBufferBuilderPool.class)
public abstract class SectionBufferBuilderPoolMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void grug$recordSize(CallbackInfo ci) {
        GrugModLoader.recordSectionBufferPoolSize(
                ((SectionBufferBuilderPool) (Object) this).getFreeBufferCount());
    }
}
