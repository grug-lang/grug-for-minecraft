package net.grug.minecraft.stationapi;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugRelitSections;
import net.grug.minecraft.stationapi.mixin.WorldRendererAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.WorldRenderer;

/**
 * The rebuild a room's relight owes the client that holds the room: every section whose baked
 * brightness the settled field changed, and the sections beside it, asked of this version's own
 * range request.
 *
 * <p>This version's light engine writes the field itself and notifies no renderer, so a section
 * whose light the settle changed keeps the mesh it was baked with, and the room draws at the
 * brightness it had before the settle (#1580). The request is per section, so the whole of each
 * section the box touches is marked, and the game's own range request grows that by one block,
 * which reaches the sections beside it.
 *
 * <p>It is a class of its own and not part of {@link StationApiAdapter} because a dedicated server
 * loads and runs that one, and Fabric Loader refuses to define a client's own class in a server
 * process (#1246): a client type on a path a server takes fails the run, which one first attempt at
 * this fix did with an {@code instanceof Minecraft} in the shared adapter. Only {@link
 * StationApiAdapter#relightRoom} calls this, and {@link ServerStationApiAdapter#relightRoom}
 * overrides that method to settle the light alone, so a server never executes the call, never loads
 * this class, and owes the rebuild to nothing: its joined client marks the light its packets carry.
 */
final class StationApiRoomRemeshClient {

    private StationApiRoomRemeshClient() {}

    /**
     * Marks every section the box touches, and the sections beside them, for compiling again. Only
     * a client runs this, and a client that runs a room build always has its renderer, since it
     * draws the world, so there is nothing here to guard.
     */
    static void markSections(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        WorldRenderer renderer =
                ((Minecraft) FabricLoader.getInstance().getGameInstance()).worldRenderer;
        for (int[] section : GrugRelitSections.sectionBoxes(minX, minY, minZ, maxX, maxY, maxZ)) {
            ((WorldRendererAccess) renderer)
                    .grug$markDirty(
                            section[0], section[1], section[2], section[3], section[4], section[5]);
        }
    }
}
