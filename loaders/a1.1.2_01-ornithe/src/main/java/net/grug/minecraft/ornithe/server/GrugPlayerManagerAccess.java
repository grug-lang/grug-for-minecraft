package net.grug.minecraft.ornithe.server;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugGenerated;

/**
 * The Alpha dedicated server's player manager, whose tick is what sends the block changes its chunk
 * map has queued.
 *
 * <p>The player manager is a class only the a0.2.1 server has, and this layer compiles against the
 * client's jar, so neither it nor its tick can be named in source: the server's own intermediary
 * names are looked up by reflection, once, in the one place that needs them (#1123). The server
 * instance comes from OSL's lifecycle API, which keeps the one it runs. A client has no player
 * manager and answers nothing, so a singleplayer run keeps its own settle without one.
 */
@GrugGenerated("the server's player manager, which only the dedicated-server job reaches")
final class GrugPlayerManagerAccess {
    /** OSL's holder of the running server, which keeps its own name in every namespace. */
    private static final String SERVER_INSTANCE =
            "net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance";

    /** The server's intermediary name of the field that holds its player manager. */
    private static final String SERVER_PLAYER_MANAGER = "f_65897993";

    /** The server's intermediary name of the player manager's tick. */
    private static final String SERVER_PLAYER_MANAGER_TICK = "m_23443588";

    private static final boolean CLIENT =
            FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

    /** The player manager, looked up on the first flush and kept for the run. */
    private static Object playerManager = null;

    private GrugPlayerManagerAccess() {}

    /** Sends the block changes the player manager has queued, and does nothing on a client. */
    static void flushQueuedChanges() {
        if (CLIENT) {
            return;
        }
        try {
            if (playerManager == null) {
                playerManager = findPlayerManager();
            }
            playerManager.getClass().getMethod(SERVER_PLAYER_MANAGER_TICK).invoke(playerManager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "The dedicated server's player manager has no "
                            + SERVER_PLAYER_MANAGER_TICK
                            + "().",
                    e);
        }
    }

    private static Object findPlayerManager() throws ReflectiveOperationException {
        Object server = Class.forName(SERVER_INSTANCE).getMethod("get").invoke(null);
        return server.getClass().getField(SERVER_PLAYER_MANAGER).get(server);
    }
}
