package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.client.GrugFrameRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Gives every grug block entity a per-frame renderer.
 *
 * <p>Neither Alpha nor Beta has a registry a mod can add a block entity renderer to: the dispatcher
 * keeps the map itself and fills it in its constructor. Adding ours at the end of that constructor
 * is the one point at which the map is complete and the dispatcher exists to hand to {@link
 * BlockEntityRenderer#init}, which is what {@code bindTexture} and {@code getTextRenderer} read.
 *
 * <p>The renderer is registered for the one grug block entity class every grug block shares, so it
 * is asked about every grug block. A block entity whose script has no {@code render_frame} is
 * declined by the engine without a report, which is why the renderer can ask all of them rather
 * than deciding for itself which blocks have a frame hook.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMixin {

    @Shadow @Final private Map<Class<?>, BlockEntityRenderer> renderers;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void grug$registerFrameRenderer(CallbackInfo ci) {
        BlockEntityRenderer renderer = new GrugFrameRenderer();
        renderer.init((BlockEntityRenderDispatcher) (Object) this);
        renderers.put(GrugBlockEntity.class, renderer);
    }
}
