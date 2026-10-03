package net.grug.minecraft.ornithe;

import net.fabricmc.api.ClientModInitializer;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.ornithe.block.GrugBlocks;
import net.grug.minecraft.ornithe.client.GrugStaticTexture;
import net.grug.minecraft.ornithe.resource.GrugResourcePackProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.crafting.GrugRecipeHelper;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;
import net.ornithemc.osl.resource.loader.api.client.ClientResourceLoaderEvents;
import net.ornithemc.osl.resource.loader.api.resource.manager.ResourceManager;

import java.io.InputStream;
import java.util.Map;

public class ClientGrugModLoader implements ClientModInitializer {
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
                                mc.textureManager.addDynamicTexture(
                                        new GrugStaticTexture(spriteId, 0, is));
                            }
                        }

                        // Upload per-face Block Textures (from model JSON) to terrain.png
                        for (Map.Entry<Integer, String> entry :
                                GrugBlocks.BLOCK_SPRITE_PATHS.entrySet()) {
                            int spriteId = entry.getKey();
                            String path = entry.getValue();
                            try (InputStream is = manager.getResource(path)) {
                                if (is != null) {
                                    mc.textureManager.addDynamicTexture(
                                            new GrugStaticTexture(spriteId, 0, is));
                                } else {
                                    // The model named this texture, so a mod shipped the reference
                                    // and not the file. Leaving the slot as the atlas had it would
                                    // render whatever a vanilla block happened to leave there.
                                    GrugModTreeDefect.report(
                                            "Block sprite "
                                                    + spriteId
                                                    + " has no texture: no mod ships "
                                                    + path
                                                    + ".");
                                }
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
                                mc.textureManager.addDynamicTexture(
                                        new GrugStaticTexture(spriteId, 1, is));
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

    private InputStream findTexture(ResourceManager manager, String type, String name) {
        String[] suffixes = {"", "_top", "_side", "_front"};
        for (String suffix : suffixes) {
            try {
                String path = "assets/grug/textures/" + type + "/" + name + suffix + ".png";
                InputStream is = manager.getResource(path);
                if (is != null) {
                    return is;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
