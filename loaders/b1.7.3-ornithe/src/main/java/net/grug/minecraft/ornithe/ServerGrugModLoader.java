package net.grug.minecraft.ornithe;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.grug.minecraft.ornithe.resource.GrugResourcePackProvider;
import net.ornithemc.osl.resource.loader.api.server.ServerResourceLoaderEvents;

public class ServerGrugModLoader implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ServerResourceLoaderEvents.INIT_RESOURCE_PACK_REPOSITORY.register(repo -> {
            repo.addSource(new GrugResourcePackProvider());
        });
    }
}
