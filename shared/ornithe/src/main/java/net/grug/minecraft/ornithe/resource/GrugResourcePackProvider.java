package net.grug.minecraft.ornithe.resource;

import net.ornithemc.osl.resource.loader.api.resource.pack.PackPosition;
import net.ornithemc.osl.resource.loader.api.resource.repository.ResourcePackRepository;
import net.ornithemc.osl.resource.loader.api.resource.repository.ResourcePackSummary;

import java.util.function.Consumer;

public class GrugResourcePackProvider implements ResourcePackRepository.Source {
    @Override
    public void loadResourcePacks(Consumer<ResourcePackSummary> consumer) {
        GrugResourcePack pack = new GrugResourcePack();
        consumer.accept(ResourcePackSummary.create(
                "grug_generated",
                true,
                true,
                PackPosition.TOP,
                () -> pack));
    }
}
