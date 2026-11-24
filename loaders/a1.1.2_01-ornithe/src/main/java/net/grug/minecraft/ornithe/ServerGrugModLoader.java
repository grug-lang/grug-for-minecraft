package net.grug.minecraft.ornithe;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.ornithe.block.entity.GrugSavedDataAlpha;

/**
 * The entrypoint of an Alpha a0.2.1 dedicated server (#1021).
 *
 * <p>It ships no resource pack: a0.2.1 has no server resource loader, and the OSL module that
 * provides one is client-only. The shapeless recipe mixin is client-only too, because the server
 * jar carries no crafting package, so recipes are registered lazily by {@link GrugServerRecipes}
 * the first time a crafting grid asks for a result (#1387). The suite is driven from the server
 * world's tick by {@code ServerWorldMixin}, so there is nothing to register here beyond saying the
 * server is up. The dedicated leg uploads the layer jar this class is in, so a later run that
 * carries its exec data over analyses the same bytes (#1674).
 */
public class ServerGrugModLoader implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        // The client keeps its own copy of a grug block entity, so a saved number the script writes
        // here is sent to it (#1093). A singleplayer game has one world and installs none.
        GrugSavedDataSync.install(new GrugSavedDataAlpha());
        GrugModLoader.LOGGER.info("grug is running on a dedicated server.");
    }
}
