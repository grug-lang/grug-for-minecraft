package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugRelitSections;
import net.grug.minecraft.ornithe.mixin.WorldRendererAccess;
import net.minecraft.client.render.world.WorldRenderer;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

/**
 * The rebuild a room's relight owes the client that holds the room: every section whose baked
 * brightness the settled field changed, and the sections beside it, asked of this version's own
 * range request.
 *
 * <p>The light of a section is what its mesh was baked from, and a loader that writes light itself
 * has to ask for the mesh again rather than rely on every one of the engine's writes having
 * notified the renderer. The request is per section, so {@link GrugRelitSections#sectionBoxes} is
 * what says which sections are owed, and this version's range request grows each of them by one
 * block, which is the section and the sections beside it.
 *
 * <p>It is a class of its own and not part of {@link OrnitheRoomLight} because a dedicated server
 * loads and runs that one, and this loader refuses to define a client's own class in a server
 * process (#1246): a client type on a path a server takes fails the run. Only {@link
 * OrnitheAdapter#relightRoom} calls this, and {@link ServerOrnitheAdapter#relightRoom} overrides
 * that method to settle the light alone, so a server never executes the call, never loads this
 * class, and owes the rebuild to nothing: its joined client marks the light its region packets
 * carry.
 */
final class OrnitheRoomRemeshClient {

    private OrnitheRoomRemeshClient() {}

    /**
     * Marks every section the box touches, and the sections beside them, for compiling again. Only
     * a client runs this, and a client that runs a room build always has its game instance and its
     * renderer, since it draws the world, so there is nothing here to guard.
     */
    static void remesh(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        WorldRenderer renderer = MinecraftInstance.get().worldRenderer;
        for (int[] section : GrugRelitSections.sectionBoxes(minX, minY, minZ, maxX, maxY, maxZ)) {
            ((WorldRendererAccess) renderer)
                    .grug$notifyRegionChanged(
                            section[0], section[1], section[2], section[3], section[4], section[5]);
        }
    }
}
