package net.grug.minecraft.ornithe;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugIdShortage;
import net.grug.minecraft.ornithe.block.entity.GrugSavedDataBeta;
import net.grug.minecraft.ornithe.resource.GrugResourcePackProvider;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.crafting.GrugRecipeHelper;
import net.ornithemc.osl.lifecycle.api.server.MinecraftServerEvents;
import net.ornithemc.osl.resource.loader.api.resource.repository.ResourcePackRepository;
import net.ornithemc.osl.resource.loader.api.server.ServerResourceLoaderEvents;

/**
 * The entrypoint of a b1.7.3 dedicated server. Besides the resource pack and the recipes, it hands
 * the server's tick to {@link GrugServerRun}, which runs the test suite when {@code GRUG_CI} is set
 * (#163). The dedicated leg uploads the layer jar this class is in, so a later run that carries its
 * exec data over analyses the same bytes (#1674).
 */
public class ServerGrugModLoader implements DedicatedServerModInitializer {
    /** Adds the mod's resource pack to the repository a server builds. */
    @GrugGenerated(
            "OSL raises this event only when the server builds a resource pack repository, which"
                    + " the measured dedicated run never does, so no coverage test can reach it")
    private static void addResourcePack(ResourcePackRepository repo) {
        repo.addSource(new GrugResourcePackProvider());
    }

    @Override
    public void onInitializeServer() {
        // The client keeps its own copy of a grug block entity, so a saved number the script writes
        // here is sent to it (#1093). A singleplayer game has one world and installs none.
        GrugSavedDataSync.install(new GrugSavedDataBeta());

        ServerResourceLoaderEvents.INIT_RESOURCE_PACK_REPOSITORY.register(
                ServerGrugModLoader::addResourcePack);

        MinecraftServerEvents.READY.register(
                server -> {
                    // A server has no screen to turn mods off on, so a shortage ends it.
                    GrugIdShortage.failIfShort();
                    GrugModLoader.LOGGER.info("Parsing JSON recipes...");
                    GrugRecipeHelper.registerAutoDiscoveredRecipes(
                            GrugModLoader.getActiveGrugModsDir());
                });

        MinecraftServerEvents.TICK_END.register(GrugServerRun::onServerTick);
    }
}
