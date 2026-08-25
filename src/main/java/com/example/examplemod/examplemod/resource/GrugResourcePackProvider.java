package com.example.examplemod.examplemod.resource;

import net.modificationstation.stationapi.api.resource.ResourceType;
import net.modificationstation.stationapi.impl.resource.ResourcePackProfile;
import net.modificationstation.stationapi.impl.resource.ResourcePackProvider;
import net.modificationstation.stationapi.impl.resource.ResourcePackSource;

import java.util.function.Consumer;

public class GrugResourcePackProvider implements ResourcePackProvider {
    public static final ResourcePackProfile.Metadata METADATA = new ResourcePackProfile.Metadata("Grug Generated", 6);

    @Override
    public void register(Consumer<ResourcePackProfile> profileAdder) {
        GrugResourcePack pack = new GrugResourcePack();
        profileAdder.accept(ResourcePackProfile.of(
                "grug_generated",
                pack.getName(),
                true,
                name -> pack,
                METADATA,
                ResourceType.CLIENT_RESOURCES,
                ResourcePackProfile.InsertionPosition.TOP,
                true,
                ResourcePackSource.BUILTIN));
    }
}
