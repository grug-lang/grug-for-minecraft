package net.grug.minecraft.forge125.client;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.forge125.block.GrugBlocks;
import net.grug.minecraft.forge125.mod_Grug;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugResourceIndex;
import net.minecraft.src.RenderEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client-only: hands the grug textures to 1.2.5's texture manager.
 *
 * <p>Split from {@link GrugBlocks} because that one also runs on a dedicated server, which has no
 * {@link RenderEngine} and therefore no atlas to put a sprite in. What the two share is the slot:
 * {@link GrugBlocks} claims one per texture file with {@code ModLoader.getUniqueSpriteIndex},
 * because {@code block.sprite} and {@code Item.setIconIndex} have to hold their sprite before the
 * block and item are registered. All that is left here is uploading pixels into a slot that already
 * exists.
 */
public final class GrugTextures {
    /** The atlas a block sprite lives in, as the inherited {@code bindImage} numbers them. */
    private static final int TERRAIN = 0;

    /** The atlas an item sprite lives in. */
    private static final int ITEMS = 1;

    /**
     * Every sprite handed to the texture manager, in the order they were registered, so a reload
     * can re-read their files without going back through the slot tables.
     */
    private static final List<GrugStaticTexture> REGISTERED = new ArrayList<>();

    private static boolean registered = false;

    @GrugGenerated("utility class: never instantiated")
    private GrugTextures() {}

    /**
     * Registers one effect per grug sprite with the render engine, once per session.
     *
     * <p>Called from the client tick rather than from {@code mod_Grug.modsLoaded} because the atlas
     * has to exist first: {@code FMLClientHandler.getTextureDimensions} decides how large a tile is
     * from the dimensions {@code RenderEngine.setupTexture} recorded, and {@code registerTextureFX}
     * asks for them before it accepts an effect. 1.2.5 only stitches {@code /terrain.png} the first
     * time something binds it, which is a world being drawn.
     */
    public static void register(RenderEngine engine) {
        if (registered) return;
        registered = true;

        for (Map.Entry<String, Integer> sprite : GrugBlocks.BLOCK_TEXTURES.entrySet()) {
            upload(engine, TERRAIN, sprite.getKey(), sprite.getValue());
        }
        for (Map.Entry<String, Integer> sprite : GrugBlocks.ITEM_TEXTURES.entrySet()) {
            upload(engine, ITEMS, sprite.getKey(), sprite.getValue());
        }

        if (!REGISTERED.isEmpty()) {
            mod_Grug.LOGGER.info(
                    "Registered " + REGISTERED.size() + " grug textures: " + REGISTERED);
        }
    }

    /**
     * Re-reads every grug texture from the mods tree into the sprite it already occupies.
     *
     * <p>Each read issues its own {@code glTexSubImage2D} rather than leaving the pixels for {@code
     * RenderEngine.updateDynamicTextures}, because that only runs while the game is unpaused and a
     * reload has to land whether or not it is. See {@link GrugStaticTexture}.
     */
    public static void reload() {
        for (GrugStaticTexture texture : REGISTERED) {
            texture.read();
        }
    }

    /**
     * Puts one file's pixels into one slot of one atlas.
     *
     * <p>Registering with the texture manager as well as uploading is what makes the sprite survive
     * a texture pack change: FML re-runs {@code setup()} on every registered effect when an atlas
     * is re-stitched, and that is what puts the grug pixels back afterwards.
     */
    private static void upload(RenderEngine engine, int atlas, String path, int sprite) {
        File file =
                GrugResourceIndex.findDirectResource(
                        GrugCore.getAdapter().getGrugModsDirectory(), path);
        if (file == null) {
            // GrugBlocks resolved this path out of the mods tree a moment ago and claimed a slot
            // for
            // it, so it cannot have stopped being there. A block rendering whatever now fills its
            // slot would be a rendering no mod asked for.
            throw Grug.fatal("The grug texture " + path + " is no longer in the mods tree.");
        }
        GrugStaticTexture texture = new GrugStaticTexture(sprite, atlas, file, engine);
        engine.registerTextureFX(texture);
        REGISTERED.add(texture);
    }
}
