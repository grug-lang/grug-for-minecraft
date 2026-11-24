package net.grug.minecraft.ornithe.server;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugLinkArms;
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
public final class GrugPlayerManagerAccess {
    /** OSL's holder of the running server, which keeps its own name in every namespace. */
    private static final String SERVER_INSTANCE =
            "net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance";

    /** The server's intermediary name of the field that holds its player manager. */
    private static final String SERVER_PLAYER_MANAGER = "f_65897993";

    /** The server's intermediary name of the player manager's tick. */
    private static final String SERVER_PLAYER_MANAGER_TICK = "m_23443588";

    private static final boolean SERVER =
            EnvType.SERVER.equals(FabricLoader.getInstance().getEnvironmentType());

    /** The server's intermediary names of the operator list's three methods, all taking a name. */
    private static final String SERVER_IS_OP = "m_55622692";

    private static final String SERVER_ADD_OP = "m_16677163";
    private static final String SERVER_REMOVE_OP = "m_26800521";

    /** The server's intermediary name of the field that holds its entity tracker. */
    private static final String SERVER_ENTITY_TRACKER = "f_33625485";

    /**
     * The entity tracker's intermediary name of its removal of an entity, which sends the destroy
     * packet.
     */
    private static final String TRACKER_REMOVE = "m_69318390";

    /** The player manager, looked up on the first flush and kept for the run. */
    private static Object playerManager = null;

    @GrugGenerated("utility class: never instantiated")
    private GrugPlayerManagerAccess() {}

    /** Sends the block changes the player manager has queued, and does nothing on a client. */
    static void flushQueuedChanges() {
        GrugLinkArms.runIf(SERVER, GrugPlayerManagerAccess::flushOnServer);
    }

    private static void flushOnServer() {
        playerManager = GrugLinkArms.orElse(playerManager, GrugPlayerManagerAccess::findManager);
        GrugLinkArms.deliver(
                playerManager, manager -> invoke(manager, SERVER_PLAYER_MANAGER_TICK, null, null));
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
        playerManager = GrugLinkArms.orElse(playerManager, GrugPlayerManagerAccess::findManager);
        return invoke(required(playerManager), method, String.class, name);
    }

    /** The manager of a running server, which every call that needs one is made after. */
    @GrugGenerated(
            "the missing arm: a call is made only while a server runs, because it is a test that"
                    + " asks, and a server that runs has its manager")
    private static Object required(Object manager) {
        if (manager == null) {
            throw new IllegalStateException(
                    "There is no running server to ask who is an operator.");
        }
        return manager;
    }

    /**
     * Calls {@code method} of {@code target} by name, with one optional argument of {@code type}.
     */
    @GrugGenerated(
            "the reflection failure arm: the names are the game's own, pinned to its version, so a"
                    + " failure means the pin is wrong and no run can make it")
    private static Object invoke(Object target, String method, Class<?> type, Object argument) {
        try {
            if (type == null) {
                return target.getClass().getMethod(method).invoke(target);
            }
            return target.getClass().getMethod(method, type).invoke(target, argument);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "The dedicated server's player manager has no " + method + "().", e);
        }
    }

    /** The running server, or null before it exists or after it ended. */
    @GrugGenerated(
            "the no-server arm: it is asked before the server exists or after it ended, which no"
                    + " test is running in; the reflection failure arm needs a wrong pinned name")
    private static Object serverInstance() {
        try {
            return Class.forName(SERVER_INSTANCE).getMethod("get").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The dedicated server could not be reached.", e);
        }
    }

    /** The running server's player manager, or null before the server exists or after it ended. */
    @GrugGenerated(
            "the no-server arm: it is asked before the server exists or after it ended, which no"
                    + " test is running in; the reflection failure arm needs a wrong pinned name")
    private static Object findManager() {
        Object server = serverInstance();
        if (server == null) {
            return null;
        }
        try {
            return server.getClass().getField(SERVER_PLAYER_MANAGER).get(server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The server's player manager could not be read.", e);
        }
    }

    /**
     * Tells the entity tracker that {@code entity} was removed, so every client that tracks it is
     * sent a destroy packet (#1096). Does nothing before the server exists.
     */
    public static void untrack(Object entity) {
        GrugLinkArms.deliver(serverInstance(), server -> untrackFrom(server, entity));
    }

    @GrugGenerated(
            "the reflection walk over the tracker's methods: its no-match throw and the failure arm"
                    + " need a wrong pinned name, which no run can make, and the walk skips the"
                    + " tracker's other methods in an order the game decides")
    private static void untrackFrom(Object server, Object entity) {
        try {
            Object tracker = server.getClass().getField(SERVER_ENTITY_TRACKER).get(server);
            for (java.lang.reflect.Method method : tracker.getClass().getMethods()) {
                if (method.getName().equals(TRACKER_REMOVE) && method.getParameterCount() == 1) {
                    method.invoke(tracker, entity);
                    return;
                }
            }
            throw new IllegalStateException(
                    "The dedicated server's entity tracker has no " + TRACKER_REMOVE + "(entity).");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "The dedicated server's entity tracker could not be reached.", e);
        }
    }
}
