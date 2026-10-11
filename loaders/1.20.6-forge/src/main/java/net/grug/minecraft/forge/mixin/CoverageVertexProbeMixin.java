package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary #294 instrumentation: logs the per-vertex AO factors and packed light values the mesher
 * writes for the coverage_render wall's faces, together with the level's own light at the cells the
 * face samples, so a local capture's log can be compared between passes and against the light the
 * render shows. The wall sits at z+6 of the graphics origin, which is z 58 with the test save this
 * loader uses. Not for committing.
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
        if (pos.getZ() != 58) return;
        if (!BuiltInRegistries.BLOCK
                .getKey(state.getBlock())
                .toString()
                .equals("grug:coverage_flat_lit")) return;
        Direction direction = quad.getDirection();
        // Log the level's light at the cell this face samples (the cell in front of it) and at the
        // face's own cell, so the logged mesh light can be checked against the level's data.
        BlockPos front = pos.relative(direction);
        StringBuilder corners = new StringBuilder();
        for (Direction side :
                new Direction[] {Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST}) {
            BlockPos corner = front.relative(side);
            corners.append(' ')
                    .append(side.getName())
                    .append('=')
                    .append(level.getBrightness(LightLayer.BLOCK, corner))
                    .append('/')
                    .append(level.getBrightness(LightLayer.SKY, corner));
        }
        GRUG_LOGGER.info(
                "[coverage-vertex2] pos={} dir={} shade={} ao={},{},{},{} light={},{},{},{}"
                        + " own={}/{} front={}/{} frontState={} corners:{}",
                pos,
                direction,
                quad.isShade(),
                ao0,
                ao1,
                ao2,
                ao3,
                grug$decode(light0),
                grug$decode(light1),
                grug$decode(light2),
                grug$decode(light3),
                level.getBrightness(LightLayer.BLOCK, pos),
                level.getBrightness(LightLayer.SKY, pos),
                level.getBrightness(LightLayer.BLOCK, front),
                level.getBrightness(LightLayer.SKY, front),
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(front).getBlock()),
                corners);
    }

    @Unique
    private static String grug$decode(int packed) {
        return ((packed >> 4) & 0xFFFF) + "/" + ((packed >> 20) & 0xFFFF);
    }
}
