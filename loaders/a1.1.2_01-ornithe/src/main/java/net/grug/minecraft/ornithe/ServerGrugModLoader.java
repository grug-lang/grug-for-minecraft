package net.grug.minecraft.ornithe;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.entity.GrugSavedDataAlpha;

/**
 * The entrypoint of an Alpha a0.2.1 dedicated server (#1021).
 *
 * <p>It ships no resource pack: a0.2.1 has no server resource loader, and the OSL module that
 * provides one is client-only. Recipes are not registered either, because the crafting manager is a
 * class the server's intermediary names differently from the client's, so the shapeless recipe
 * mixin is client-only too. The suite is driven from the server world's tick by {@code
 * ServerWorldMixin}, so there is nothing to register here beyond saying the server is up.
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class ServerGrugModLoader implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        // The client keeps its own copy of a grug block entity, so a saved number the script writes
        // here is sent to it (#1093). A singleplayer game has one world and installs none.
        GrugSavedDataSync.install(new GrugSavedDataAlpha());
        GrugModLoader.LOGGER.info("grug is running on a dedicated server.");
    }
}
