package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.platform.NativeImage;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the lightmap's block-light flicker while a screenshot run owns the client.
 *
 * <p>{@code LightTexture.tick} walks {@code blockLightRedFlicker} by a small random step every
 * frame, and {@code updateLightTexture} multiplies the block-light color by {@code
 * blockLightRedFlicker + 1.5}. The whole block-lit scene therefore rides that walk, which is what
 * makes the same capture land about a light step brighter or darker from run to run. It is the
 * flicker 1.2.5 pins through {@code GrugTorchFlicker}, at a tenth of the step, and it is pinned the
 * same way: the value is zeroed, which is the neutral 1.5 factor, right before the lightmap is
 * built. See #253.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {

    @Shadow private float blockLightRedFlicker;

    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void grug$pinBlockLightFlicker(float partialTicks, CallbackInfo ci) {
        if (GrugModLoader.isTestRunActive()) {
            this.blockLightRedFlicker = 0.0F;
        }
    }

    /**
     * Temporary #294 experiment, not for committing: with {@code GRUG_LIGHTMAP=flat|black|red},
     * replaces the freshly built lightmap with one colour and re-uploads it, so a capture can say
     * whether the wall's radial field comes from the lightmap sample or from something after it. A
     * field that persists on a red lightmap is after the sample; one that disappears was the sample
     * itself.
     */
    @Inject(method = "updateLightTexture", at = @At("RETURN"))
    private void grug$flattenLightmap(float partialTicks, CallbackInfo ci) {
        if (!GrugModLoader.isTestRunActive()) return;
        String mode = System.getenv("GRUG_LIGHTMAP");
        if (mode == null) return;
        // Only while the coverage_render fixture's wall is in the world, so the tests that capture
        // before it (buildcraft's, which sort first) are unaffected and the run reaches the capture
        // this experiment is about.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        BlockPos wallPos = new BlockPos(0, 68, 58);
        if (!BuiltInRegistries.BLOCK
                .getKey(minecraft.level.getBlockState(wallPos).getBlock())
                .toString()
                .equals("grug:coverage_flat_lit")) {
            return;
        }
        LightTextureAccess access = (LightTextureAccess) (Object) this;
        NativeImage pixels = access.grug$lightPixels();
        int width = pixels.getWidth();
        int height = pixels.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel;
                if (mode.equals("red")) {
                    pixel = 0xFF0000FF;
                } else {
                    int value = mode.equals("black") ? 0 : 128;
                    pixel = 0xFF000000 | (value << 16) | (value << 8) | value;
                }
                pixels.setPixelRGBA(x, y, pixel);
            }
        }
        access.grug$dynamicTexture().upload();
    }
}
