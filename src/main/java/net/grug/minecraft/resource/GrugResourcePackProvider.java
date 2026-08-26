package net.grug.minecraft.resource;

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

        // Register for client-side assets (models, textures, lang)
        profileAdder.accept(ResourcePackProfile.of(
                "grug_generated_assets",
                pack.getName() + " (Assets)",
                true,
                name -> pack,
                METADATA,
                ResourceType.CLIENT_RESOURCES,
                ResourcePackProfile.InsertionPosition.TOP,
                true,
                ResourcePackSource.BUILTIN));

        // Register for server-side data (recipes, tags)
        profileAdder.accept(ResourcePackProfile.of(
                "grug_generated_data",
                pack.getName() + " (Data)",
                true,
                name -> pack,
                METADATA,
                ResourceType.SERVER_DATA,
                ResourcePackProfile.InsertionPosition.TOP,
                true,
                ResourcePackSource.BUILTIN));
    }
}
