package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Temporary #294 instrumentation: reads the lightmap's pixels and the block-light flicker so a
 * local capture can dump both, and reaches the dynamic texture so the flatten experiment can
 * re-upload a modified lightmap. Not for committing.
 */
@Mixin(LightTexture.class)
public interface LightTextureAccess {

    @Accessor("lightPixels")
    NativeImage grug$lightPixels();

    @Accessor("blockLightRedFlicker")
    float grug$flicker();

    @Accessor("lightTexture")
    DynamicTexture grug$dynamicTexture();
}
