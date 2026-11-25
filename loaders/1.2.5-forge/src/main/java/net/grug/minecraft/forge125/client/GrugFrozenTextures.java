package net.grug.minecraft.forge125.client;

import cpw.mods.fml.client.FMLTextureFX;

import net.minecraft.src.RenderEngine;
import net.minecraft.src.TextureFX;
import net.minecraft.src.TextureLavaFX;
import net.minecraft.src.TextureLavaFlowFX;
import net.minecraft.src.TextureWaterFX;
import net.minecraft.src.TextureWaterFlowFX;

import java.util.List;

/**
 * Replaces the game's four liquid animations with the texture pack's own still tile while a capture
 * run owns the client.
 *
 * <p>1.2.5 draws water and lava by generating new pixels into their atlas slot every tick, and each
 * {@code onTick} draws from {@code Math.random()}, so the slot holds a different picture in every
 * run: no committed golden could match the next run, and the reference job, which compares its live
 * capture with a golden exactly, could not match at all. Pinning one generated frame is no better,
 * because that frame is random too. What is the same in every run is the pack's own tile, which is
 * what the other loaders' frozen sprites show, so each effect is replaced with a static one holding
 * that tile. The atlas then uploads the same pixels every tick without animating anything.
 *
 * <p>Fire and the nether portal are left alone: fire draws from {@code Math.random()}, and the
 * portal's frame counter is private and survives its {@code setup()}, so neither has a fixed frame
 * to pin to. The compass and the clock follow the player and the clock rather than the number of
 * frames a run has drawn. No committed capture shows any of them. See {@code
 * GrugReference.isAnimationFrozen()} and #1771.
 */
public final class GrugFrozenTextures {
    private GrugFrozenTextures() {}

    /** The width of a terrain atlas in tiles, as 1.2.5's own upload maths counts it. */
    private static final int ATLAS_COLUMNS = 16;

    /**
     * Replaces every liquid animation in {@code engine}'s list with a still copy of its pack tile.
     *
     * <p>Callable once a run starts and again on every frame after it: a replaced entry is a plain
     * {@link TextureFX} that matches none of the four classes, so the next call leaves it alone.
     */
    public static void pin(RenderEngine engine) {
        @SuppressWarnings("unchecked")
        List<TextureFX> effects = engine.textureList;
        int[] terrain = engine.getTextureContents("/terrain.png");
        for (int i = 0; i < effects.size(); i++) {
            TextureFX effect = (TextureFX) effects.get(i);
            if (!isLiquidAnimation(effect)) {
                continue;
            }
            effects.set(i, new GrugStillTexture(effect, terrain));
        }
    }

    /** Whether {@code effect} is one of the four animations 1.2.5 generates for water and lava. */
    private static boolean isLiquidAnimation(TextureFX effect) {
        return effect instanceof TextureWaterFX
                || effect instanceof TextureWaterFlowFX
                || effect instanceof TextureLavaFX
                || effect instanceof TextureLavaFlowFX;
    }

    /**
     * One liquid animation's sprite, holding the still tile of the pack's terrain atlas instead of
     * a frame that changes every tick.
     */
    private static final class GrugStillTexture extends TextureFX {
        GrugStillTexture(TextureFX source, int[] terrain) {
            super(source.iconIndex);
            this.tileSize = source.tileSize;

            // A pack may draw larger tiles than the default 16, and FML has already told the
            // animation its own tile size, so the tile is read at the pack's size rather than 16.
            int tile = ((FMLTextureFX) source).tileSizeBase;
            int stride = tile * ATLAS_COLUMNS;
            int originX = (source.iconIndex % ATLAS_COLUMNS) * tile;
            int originY = (source.iconIndex / ATLAS_COLUMNS) * tile;

            this.imageData = new byte[tile * tile * 4];
            for (int y = 0; y < tile; y++) {
                for (int x = 0; x < tile; x++) {
                    // The atlas stores one ARGB int per pixel and the upload reads RGBA bytes.
                    int argb = terrain[(originY + y) * stride + originX + x];
                    int at = (y * tile + x) * 4;
                    this.imageData[at] = (byte) (argb >> 16);
                    this.imageData[at + 1] = (byte) (argb >> 8);
                    this.imageData[at + 2] = (byte) argb;
                    this.imageData[at + 3] = (byte) (argb >> 24);
                }
            }
        }
    }
}
