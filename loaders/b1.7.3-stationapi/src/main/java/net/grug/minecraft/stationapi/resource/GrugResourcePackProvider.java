package net.grug.minecraft.stationapi.resource;

import com.google.common.collect.ImmutableSet;

import net.modificationstation.stationapi.api.resource.ResourceType;
import net.modificationstation.stationapi.impl.resource.ResourcePackProfile;
import net.modificationstation.stationapi.impl.resource.ResourcePackProvider;
import net.modificationstation.stationapi.impl.resource.ResourcePackSource;

import java.util.function.Consumer;

public class GrugResourcePackProvider implements ResourcePackProvider {
    public static final ResourcePackProfile.Metadata METADATA =
            new ResourcePackProfile.Metadata("Grug Generated", 6);

    /**
     * Appends this mod's provider to the loader's built-in list. Called from the resource-pack
     * mixin so the loop stays measurable instead of living in an uninstrumentable mixin class.
     */
    public static ImmutableSet<ResourcePackProvider> withGrugProvider(Object[] providers) {
        ImmutableSet.Builder<ResourcePackProvider> builder = ImmutableSet.builder();

        for (Object provider : providers) {
            builder.add((ResourcePackProvider) provider);
        }

        builder.add(new GrugResourcePackProvider());

        return builder.build();
    }

    @Override
    public void register(Consumer<ResourcePackProfile> profileAdder) {
        GrugResourcePack pack = new GrugResourcePack();

        // Register for client-side assets (models, textures, lang)
        profileAdder.accept(
                ResourcePackProfile.of(
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
        profileAdder.accept(
                ResourcePackProfile.of(
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
