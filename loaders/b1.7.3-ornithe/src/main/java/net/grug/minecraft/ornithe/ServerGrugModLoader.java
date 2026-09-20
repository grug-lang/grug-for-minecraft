package net.grug.minecraft.ornithe;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.grug.minecraft.ornithe.resource.GrugResourcePackProvider;
import net.minecraft.crafting.GrugRecipeHelper;
import net.ornithemc.osl.lifecycle.api.server.MinecraftServerEvents;
import net.ornithemc.osl.resource.loader.api.server.ServerResourceLoaderEvents;

public class ServerGrugModLoader implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ServerResourceLoaderEvents.INIT_RESOURCE_PACK_REPOSITORY.register(repo -> {
            repo.addSource(new GrugResourcePackProvider());
        });

        MinecraftServerEvents.READY.register(server -> {
            GrugModLoader.LOGGER.info("Parsing JSON recipes...");
            GrugRecipeHelper.registerAutoDiscoveredRecipes(GrugModLoader.getActiveGrugModsDir());
        });
    }
}
