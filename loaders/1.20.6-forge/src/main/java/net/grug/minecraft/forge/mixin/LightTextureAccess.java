package net.grug.minecraft.forge.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Temporary #294 instrumentation: reads the lightmap's pixels and the block-light flicker so a
 * local capture can dump both. Not for committing.
 */
@Mixin(LightTexture.class)
public interface LightTextureAccess {

    @Accessor("lightPixels")
    NativeImage grug$lightPixels();

    @Accessor("blockLightRedFlicker")
    float grug$flicker();
}
