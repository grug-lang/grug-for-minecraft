package net.grug.minecraft.ornithe;

import net.fabricmc.api.ClientModInitializer;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.ornithe.block.GrugBlocks;
import net.grug.minecraft.ornithe.client.GrugStaticTexture;
import net.grug.minecraft.ornithe.resource.GrugResourcePackProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.texture.TextureManager;
import net.minecraft.crafting.GrugRecipeHelper;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;
import net.ornithemc.osl.resource.loader.api.client.ClientResourceLoaderEvents;
import net.ornithemc.osl.resource.loader.api.resource.manager.ResourceManager;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class ClientGrugModLoader implements ClientModInitializer {

    /** The atlas a sprite belongs to: 0 is terrain.png and 1 is gui/items.png. */
    private static final int TERRAIN = 0;

    private static final int ITEMS = 1;

    /**
     * The grug sprite already living in each slot of each atlas, so a reload refreshes the pixels of
     * the sprite it already has rather than adding another one.
     *
     * <p>Keyed by sprite index rather than by the file the texture came from, because that index is
     * the block's identity: two blocks can name the same texture and each gets its own index, so a
     * reload that looked its slot up by path would refresh one of them and leave the other stale.
     * The two atlases are separate maps because their indices come from separate counters that both
     * start at the same number.
     */
    private static final Map<Integer, GrugStaticTexture> TERRAIN_SPRITES = new HashMap<>();

    private static final Map<Integer, GrugStaticTexture> ITEM_SPRITES = new HashMap<>();

    @Override
    public void onInitializeClient() {
        ClientResourceLoaderEvents.INIT_RESOURCE_PACK_REPOSITORY.register(
                repo -> {
                    repo.addSource(new GrugResourcePackProvider());
                });

        ClientResourceLoaderEvents.END_RESOURCE_RELOAD.register(
                (manager, ctx) -> {
                    Minecraft mc = MinecraftInstance.get();
                    if (mc != null && mc.textureManager != null) {
                        // Upload Block Textures to terrain.png
                        for (Map.Entry<String, Integer> entry :
                                GrugBlocks.BLOCK_SPRITES.entrySet()) {
                            String name = entry.getKey();
                            int spriteId = entry.getValue();
                            InputStream is = findTexture(manager, "block", name);
                            if (is != null) {
                                upload(mc.textureManager, TERRAIN, spriteId, is);
                            }
                        }

                        // Upload per-face Block Textures (from model JSON) to terrain.png
                        for (Map.Entry<Integer, String> entry :
                                GrugBlocks.BLOCK_SPRITE_PATHS.entrySet()) {
                            int spriteId = entry.getKey();
                            String path = entry.getValue();
                            try (InputStream is = manager.getResource(path)) {
                                upload(mc.textureManager, TERRAIN, spriteId, is);
                            } catch (java.io.FileNotFoundException missing) {
                                // The model named this texture, so a mod shipped the reference and
                                // not the file. Leaving the slot as the atlas had it would render
                                // whatever a vanilla block happened to leave there.
                                GrugModTreeDefect.report(
                                        "Block sprite "
                                                + spriteId
                                                + " has no texture: no mod ships "
                                                + path
                                                + ".");
                            } catch (Exception e) {
                                throw Grug.fatal("Failed to load the block texture " + path, e);
                            }
                        }

                        // Upload Item Textures to gui/items.png
                        for (Map.Entry<String, Integer> entry :
                                GrugBlocks.ITEM_SPRITES.entrySet()) {
                            String name = entry.getKey();
                            int spriteId = entry.getValue();
                            InputStream is = findTexture(manager, "item", name);
                            if (is != null) {
                                upload(mc.textureManager, ITEMS, spriteId, is);
                            }
                        }
                    }
                });

        MinecraftClientEvents.READY.register(
                minecraft -> {
                    GrugModLoader.LOGGER.info("Parsing JSON recipes...");
                    GrugRecipeHelper.registerAutoDiscoveredRecipes(
                            GrugModLoader.getActiveGrugModsDir());
                });
    }

    /**
     * Puts {@code is}'s pixels into {@code spriteId}'s slot of {@code atlas}, registering the sprite
     * with the texture manager the first time it is seen.
     *
     * <p>No OpenGL happens here. The texture manager re-uploads every dynamic texture it holds on
     * every tick, so the pixels land in the atlas on the next tick, which also means a slot that
     * fails to read keeps rendering the texture that was in it rather than going blank.
     */
    private static void upload(
            TextureManager textures, int atlas, int spriteId, InputStream is) {
        Map<Integer, GrugStaticTexture> sprites = atlas == TERRAIN ? TERRAIN_SPRITES : ITEM_SPRITES;
        GrugStaticTexture sprite = sprites.get(spriteId);
        if (sprite == null) {
            sprite = new GrugStaticTexture(spriteId, atlas);
            sprites.put(spriteId, sprite);
            textures.addDynamicTexture(sprite);
        }
        sprite.read(is);
    }

    /**
     * The texture a block or item with no model resolves to, or null when it ships none.
     *
     * <p>A grug mod's assets are flat: {@code assets/grug/textures/<type>/<name>.png}, or one of
     * the {@code _top}, {@code _side} and {@code _front} variants beside it. Looking for those is a
     * search, so a name no mod ships is an answer rather than a failure, and nothing is reported: a
     * block whose model gives no per-face textures has this and nothing else to draw with, which is
     * a difference from a game that reads the model itself rather than a broken tree. What is not
     * swallowed is a failure that is not "not there", because a name no mod ships and a resource
     * manager that cannot answer are different things.
     */
    private InputStream findTexture(ResourceManager manager, String type, String name) {
        for (String suffix : new String[] {"", "_top", "_side", "_front"}) {
            String path = "assets/grug/textures/" + type + "/" + name + suffix + ".png";
            try {
                InputStream is = manager.getResource(path);
                if (is != null) {
                    return is;
                }
            } catch (java.io.FileNotFoundException missing) {
                // The next name beside it may be the one this block ships.
            } catch (Exception e) {
                throw Grug.fatal("Failed to read " + path, e);
            }
        }
        return null;
    }
}
