package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.grug.minecraft.forge.block.GrugBlock;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;
import java.util.List;

/**
 * Lights the quads of a block that declared set_flat_lighting at the light of its own position.
 *
 * <p>The game's flat-light path, {@code renderModelFaceFlat}, picks each quad's light from the
 * block the quad faces, or from the block itself only when the quad is not on the block's boundary
 * and the block's collision shape is not a full cube. A grug block has a full collision shape, so
 * every face of it borrows a neighbour's light, which is the opposite of what BuildCraft 2.2.14's
 * engine renderer does with GL lighting off: one brightness, the light at the block's own position.
 * The block is not opaque, so that position holds real light, as it does on the other loaders. See
 * #395.
 *
 * <p>For such a block the method is replaced by the same loop, which differs only in the light it
 * hands to {@code putQuadData}. Every other block goes through the game's own method untouched.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {

    @Shadow
    protected abstract void putQuadData(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            VertexConsumer consumer,
            PoseStack.Pose pose,
            BakedQuad quad,
            float brightness0,
            float brightness1,
            float brightness2,
            float brightness3,
            int light0,
            int light1,
            int light2,
            int light3,
            int overlay);

    @Inject(method = "renderModelFaceFlat", at = @At("HEAD"), cancellable = true)
    private void grug$lightFlatBlockAtItsOwnPosition(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            int packedLight,
            int packedOverlay,
            boolean repackLight,
            PoseStack poseStack,
            VertexConsumer consumer,
            List<BakedQuad> quads,
            BitSet shapeFlags,
            CallbackInfo ci) {
        if (!(state.getBlock() instanceof GrugBlock block) || !block.drawsFlatLighting()) {
            return;
        }

        int light = LevelRenderer.getLightColor(level, state, pos);
        for (BakedQuad quad : quads) {
            float shade = level.getShade(quad.getDirection(), quad.isShade());
            this.putQuadData(
                    level,
                    state,
                    pos,
                    consumer,
                    poseStack.last(),
                    quad,
                    shade,
                    shade,
                    shade,
                    shade,
                    light,
                    light,
                    light,
                    light,
                    packedOverlay);
        }
        ci.cancel();
    }
}
