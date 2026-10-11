package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary #294 instrumentation: logs the per-vertex AO factors and packed light values the
 * mesher writes for the coverage_render wall's north faces inside the crop, so a local capture's
 * log can be compared between passes. The wall sits at z+6 of the graphics origin, which is z 58
 * with the test save this loader uses, and the crop covers block columns x -2 to 3 at y 68 to 69.
 * Not for committing.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class CoverageVertexProbeMixin {

    @Unique private static final Logger GRUG_LOGGER = LogUtils.getLogger();

    @Inject(method = "putQuadData", at = @At("HEAD"))
    private void grug$logWallQuad(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            VertexConsumer consumer,
            PoseStack.Pose pose,
            BakedQuad quad,
            float ao0,
            float ao1,
            float ao2,
            float ao3,
            int light0,
            int light1,
            int light2,
            int light3,
            int overlay,
            CallbackInfo ci) {
        if (!GrugModLoader.isTestRunActive()) return;
        if (quad.getDirection() != Direction.NORTH) return;
        if (pos.getZ() != 58) return;
        if (pos.getX() < -2 || pos.getX() > 3 || pos.getY() < 68 || pos.getY() > 69) return;
        GRUG_LOGGER.info(
                "[coverage-vertex] pos={} ao={},{},{},{} light={},{},{},{}",
                pos,
                ao0,
                ao1,
                ao2,
                ao3,
                grug$decode(light0),
                grug$decode(light1),
                grug$decode(light2),
                grug$decode(light3));
    }

    @Unique
    private static String grug$decode(int packed) {
        return ((packed >> 4) & 0xFFFF) + "/" + ((packed >> 20) & 0xFFFF);
    }
}
