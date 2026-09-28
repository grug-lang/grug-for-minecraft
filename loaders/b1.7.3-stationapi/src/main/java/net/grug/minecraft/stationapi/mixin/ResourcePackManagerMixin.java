package net.grug.minecraft.stationapi.mixin;

import com.google.common.collect.ImmutableSet;

import net.grug.minecraft.stationapi.resource.GrugResourcePackProvider;
import net.modificationstation.stationapi.impl.resource.ResourcePackManager;
import net.modificationstation.stationapi.impl.resource.ResourcePackProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ResourcePackManager.class)
public class ResourcePackManagerMixin {

    @Redirect(
            method = "<init>",
            remap = false,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/google/common/collect/ImmutableSet;copyOf([Ljava/lang/Object;)Lcom/google/common/collect/ImmutableSet;",
                            remap = false))
    private ImmutableSet<ResourcePackProvider> addProvider(Object[] providers) {
        // The list building lives in GrugResourcePackProvider so JaCoCo can measure it.
        return GrugResourcePackProvider.withGrugProvider(providers);
    }
}
