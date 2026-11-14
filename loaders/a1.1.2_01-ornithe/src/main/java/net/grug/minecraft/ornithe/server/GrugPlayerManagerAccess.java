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
 *
 * <p>The same manager holds the operator list, which {@code ops.txt} fills: {@code isOp}
 * lower-cases a name and looks it up, and {@code addOp} and {@code removeOp} change the set and
 * save the file (#1331). They are reached the same way, by intermediary name, for the same reason.
 */
@GrugGenerated("the server's player manager, which only the dedicated-server job reaches")
public final class GrugPlayerManagerAccess {
    /** OSL's holder of the running server, which keeps its own name in every namespace. */
    private static final String SERVER_INSTANCE =
            "net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance";

    /** The server's intermediary name of the field that holds its player manager. */
    private static final String SERVER_PLAYER_MANAGER = "f_65897993";

    /** The server's intermediary name of the player manager's tick. */
    private static final String SERVER_PLAYER_MANAGER_TICK = "m_23443588";

    private static final boolean CLIENT =
            FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

    /** The server's intermediary names of the operator list's three methods, all taking a name. */
    private static final String SERVER_IS_OP = "m_55622692";

    private static final String SERVER_ADD_OP = "m_16677163";
    private static final String SERVER_REMOVE_OP = "m_26800521";

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
            if (playerManager == null) {
                return;
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

    /** Whether the server's operator list holds {@code name}. */
    public static boolean isOp(String name) {
        return (Boolean) callWithName(SERVER_IS_OP, name);
    }

    /** Adds {@code name} to the server's operator list, which the game saves to {@code ops.txt}. */
    public static void addOp(String name) {
        callWithName(SERVER_ADD_OP, name);
    }

    /** Removes {@code name} from the operator list, which the game saves to {@code ops.txt}. */
    public static void removeOp(String name) {
        callWithName(SERVER_REMOVE_OP, name);
    }

    /** Calls one of the manager's methods that takes a name, and answers what it answers. */
    private static Object callWithName(String method, String name) {
        try {
            if (playerManager == null) {
                playerManager = findPlayerManager();
            }
            if (playerManager == null) {
                throw new IllegalStateException(
                        "There is no running server to ask who is an operator.");
            }
            return playerManager
                    .getClass()
                    .getMethod(method, String.class)
                    .invoke(playerManager, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "The dedicated server's player manager has no " + method + "(String).", e);
        }
    }

    /** The running server's player manager, or null before the server exists or after it ended. */
    private static Object findPlayerManager() throws ReflectiveOperationException {
        Object server = Class.forName(SERVER_INSTANCE).getMethod("get").invoke(null);
        if (server == null) {
            return null;
        }
        return server.getClass().getField(SERVER_PLAYER_MANAGER).get(server);
    }
}
