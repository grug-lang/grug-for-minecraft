package net.grug.minecraft.ornithe;

import net.fabricmc.api.ClientModInitializer;
import net.grug.minecraft.ornithe.resource.GrugResourcePackProvider;
import net.ornithemc.osl.resource.loader.api.client.ClientResourceLoaderEvents;

public class ClientGrugModLoader implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientResourceLoaderEvents.INIT_RESOURCE_PACK_REPOSITORY.register(repo -> {
            repo.addSource(new GrugResourcePackProvider());
        });
    }
}
