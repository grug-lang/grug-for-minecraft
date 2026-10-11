package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporary probe for #270 step 4: logs the block light of the cells the coverage_render wall's
 * front faces sample, and the camera's and the player's positions, once a second, so a repeats
 * run's pass 1 can be compared with pass 2+.
 *
 * <p>The fixture places a thirteen square wall of {@code grug:coverage_flat_lit} at the graphics
 * origin plus {@code z + 6}. The origin is the player's eye plus three, in y only, so the wall is at
 * camera {@code z + 6} and the cells just in front of it, the ones its faces sample, are at camera
 * {@code z + 5}, {@code x - 6} to {@code x + 6} and {@code y - 6} to {@code y + 6}. Only cells below
 * the maximum are printed: the wall emits the maximum, so a cell that is not at 15 is a leftover or
 * a gap, which is the shape of the difference #262 and #264 describe. The first dispatch of this
 * probe scanned {@code z + 8}, two blocks behind the wall, because its comment assumed the origin's
 * three was added to every component; the reading was the space behind the wall.
 *
 * <p>Not for committing: this is measurement scaffolding for the step's experiment branch.
 */
@Mixin(LightTexture.class)
public abstract class CoverageLightProbeMixin {

    @Unique private static long grug$lastLogSecond = -1L;

    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void grug$logWallLight(float partialTicks, CallbackInfo ci) {
        if (!GrugModLoader.isTestRunActive()) return;

        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) return;

        long second = System.currentTimeMillis() / 1000L;
        if (second == grug$lastLogSecond) return;
        grug$lastLogSecond = second;

        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        int cx = Mth.floor(camera.x);
        int cy = Mth.floor(camera.y);
        int cz = Mth.floor(camera.z);

        StringBuilder line = new StringBuilder("[coverage-light] camera=" + camera
                + " player=" + (minecraft.player == null ? "none" : minecraft.player.position())
                + " yaw=" + (minecraft.player == null ? 0.0f : minecraft.player.getYRot())
                + " pitch=" + (minecraft.player == null ? 0.0f : minecraft.player.getXRot())
                + " below-max on the wall's front plane:");
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -7; dx <= 7; dx++) {
            for (int dy = -7; dy <= 7; dy++) {
                pos.set(cx + dx, cy + dy, cz + 5);
                int block = level.getBrightness(LightLayer.BLOCK, pos);
                if (block < 15) {
                    line.append(' ').append(dx).append(',').append(dy).append('=').append(block);
                }
            }
        }
        System.out.println(line);
    }
}
