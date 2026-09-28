package net.grug.minecraft.forge.mixin;

import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.ForgeHooksClient;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Guards the upstream vanilla/Forge atlas race tracked by issue #38.
 *
 * <p>{@code TextureAtlas.upload} calls {@code clearTextureData()} (which sets {@code texturesByName
 * = Map.of()} and {@code missingSprite = null}) and only installs the new sprite data afterwards. A
 * background section rebuild that tesselates a liquid during that window calls {@code
 * ForgeHooksClient.getFluidSprites -> TextureAtlas.getSprite}, which throws {@code "Tried to lookup
 * sprite, but atlas is not initialized"} and kills the client.
 *
 * <p>When that happens, fall back to the icons cached by {@code setupSprites} on the last reload
 * instead of crashing. The reload that opened the window also calls {@code
 * LevelRenderer.allChanged()} once it completes, which rebuilds every section, so the affected mesh
 * is corrected on the next frame.
 */
@Mixin(LiquidBlockRenderer.class)
public abstract class LiquidBlockRendererMixin {

    @Unique private static final Logger GRUG_LOGGER = LogUtils.getLogger();

    @Unique private static boolean grug$guardLogged = false;

    @Shadow private TextureAtlasSprite[] lavaIcons;

    @Shadow private TextureAtlasSprite[] waterIcons;

    @Shadow private TextureAtlasSprite waterOverlay;

    @Redirect(
            method = "tesselate",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraftforge/client/ForgeHooksClient;getFluidSprites(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;)[Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"),
            remap = false)
    private TextureAtlasSprite[] grug$fluidSpritesOrCached(
            BlockAndTintGetter level, BlockPos pos, FluidState fluid) {
        if (!grug$guardLogged) {
            grug$guardLogged = true;
            GRUG_LOGGER.info("Fluid sprite guard is active (see issue #38)");
        }

        try {
            return ForgeHooksClient.getFluidSprites(level, pos, fluid);
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("atlas is not initialized")) {
                throw e;
            }

            boolean lava = fluid.is(FluidTags.LAVA);
            TextureAtlasSprite[] cached = lava ? this.lavaIcons : this.waterIcons;
            if (cached[0] == null || cached[1] == null) {
                // No cached icons yet, so there is nothing safe to draw with.
                throw e;
            }

            GRUG_LOGGER.info(
                    "Block atlas was mid-upload while a section was rebuilt; using cached fluid"
                            + " sprites (see issue #38)");
            return new TextureAtlasSprite[] {cached[0], cached[1], lava ? null : this.waterOverlay};
        }
    }
}
