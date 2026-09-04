package net.grug.minecraft.ornithe;

import net.fabricmc.api.ClientModInitializer;
import net.grug.minecraft.ornithe.block.GrugBlocks;
import net.grug.minecraft.ornithe.client.GrugStaticTexture;
import net.grug.minecraft.ornithe.resource.GrugResourcePackProvider;
import net.minecraft.client.Minecraft;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;
import net.ornithemc.osl.resource.loader.api.client.ClientResourceLoaderEvents;
import net.ornithemc.osl.resource.loader.api.resource.manager.ResourceManager;

import java.io.InputStream;
import java.util.Map;

public class ClientGrugModLoader implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientResourceLoaderEvents.INIT_RESOURCE_PACK_REPOSITORY.register(repo -> {
            repo.addSource(new GrugResourcePackProvider());
        });

        ClientResourceLoaderEvents.END_RESOURCE_RELOAD.register((manager, ctx) -> {
            Minecraft mc = MinecraftInstance.get();
            if (mc != null && mc.textureManager != null) {
                // Upload Block Textures to terrain.png
                for (Map.Entry<String, Integer> entry : GrugBlocks.BLOCK_SPRITES.entrySet()) {
                    String name = entry.getKey();
                    int spriteId = entry.getValue();
                    InputStream is = findTexture(manager, "block", name);
                    if (is != null) {
                        mc.textureManager.addDynamicTexture(new GrugStaticTexture(spriteId, 0, is));
                    }
                }

                // Upload Item Textures to gui/items.png
                for (Map.Entry<String, Integer> entry : GrugBlocks.ITEM_SPRITES.entrySet()) {
                    String name = entry.getKey();
                    int spriteId = entry.getValue();
                    InputStream is = findTexture(manager, "item", name);
                    if (is != null) {
                        mc.textureManager.addDynamicTexture(new GrugStaticTexture(spriteId, 1, is));
                    }
                }
            }
        });
    }

    private InputStream findTexture(ResourceManager manager, String type, String name) {
        // Fallback checks for _top, _side, etc. in case the exact name isn't found
        String[] suffixes = { "", "_top", "_side", "_front" };
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
