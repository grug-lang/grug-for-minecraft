package net.grug.minecraft.ornithe.control;

import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Alpha half of the test-control channel (#1040, option A): the server side that forwards a
 * client-only call and waits for the answer with the world frozen. All decisions live in core's
 * {@link GrugControlServer}. The client side is {@link GrugControlAlphaClient}, which a dedicated
 * server never loads.
 *
 * <p>This class names no client class, so the dedicated server's adapter can build the forwarded
 * screen calls and camera from it (#1246).
 *
 * <p>The freeze is the server's game thread blocking inside {@code capture}. It does not tick, so
 * it does not call {@code Connection.tick}, whose counter of ticks without a packet is the only
 * timeout Alpha's connection has (1200). The answer is read on the reader thread, as {@link
 * GrugControlPacket#read} says, so nothing drains the connection by hand.
 *
 * <p>The sender of an answer is the connection the reader thread belongs to, and core's server
 * accepts an answer only from the connection that started the run. Before a capture and before a
 * settle ask the server sends the block changes its player manager has queued, whose own tick a
 * frozen one never reaches, so a test that places a block and captures in the same tick reads the
 * client's frame with that block in it (#1123). Sound and screen calls do not flush, because they
 * depend on no world state.
 */
@GrugGenerated(
        "the Alpha channel glue of #1040: no test the coverage gate counts can reach it, since only"
                + " the multiplayer job runs it; every decision in it lives in core's"
                + " GrugControlServer and GrugControlClient")
public final class GrugControlAlpha {
    static final long DEFAULT_TIMEOUT_MILLIS = 20_000;

    private static GrugControlServer server;

    private GrugControlAlpha() {}

    public static synchronized GrugControlServer server() {
        if (server == null) {
            server =
                    new GrugControlServer(
                            new AlphaLink(),
                            GrugControlFaults.intFromEnv(
                                    "GRUG_CI_CONTROL_TIMEOUT_MILLIS",
                                    (int) DEFAULT_TIMEOUT_MILLIS));
        }
        return server;
    }

    /** The player each connection belongs to, learnt when its first control message is handled. */
    private static final Map<Connection, PlayerEntity> PLAYERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The player who started the run, or null when none has or they have left. */
    public static PlayerEntity starterPlayer() {
        Object starter = server().starter();
        return starter == null ? null : PLAYERS.get((Connection) starter);
    }

    /**
     * The connection a player holds, from the one its first control message came on. The handler
     * that message came through is the only place this layer can learn the pair, since the server's
     * player manager is in classes a client never loads.
     */
    public static Connection connectionFor(PlayerEntity player) {
        synchronized (PLAYERS) {
            for (Map.Entry<Connection, PlayerEntity> entry : PLAYERS.entrySet()) {
                if (entry.getValue() == player) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * The connection a player holds, whether or not it has started a control run (#1098). The GUI
     * is opened for any joined player, and a player who never started a run has sent no control
     * message, so the control channel has not met them; the player's own network handler is asked
     * instead. That handler cannot be named here, because this layer is compiled against the
     * client, which has no server classes; its fields are found by what they hold.
     */
    public static Connection connectionOfPlayer(PlayerEntity player) {
        Connection known = connectionFor(player);
        if (known != null) {
            return known;
        }
        Connection direct = connectionIn(player);
        return direct != null ? direct : nestedConnectionIn(player);
    }

    /** The connection a player's own network-handler field holds, or null before it has one. */
    private static Connection nestedConnectionIn(PlayerEntity player) {
        for (Class<?> type = player.getClass();
                type != null && !isJdk(type);
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!PacketHandler.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                try {
                    Connection connection = connectionIn(field.get(player));
                    if (connection != null) {
                        return connection;
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "cannot read " + field + " to find the connection", e);
                }
            }
        }
        return null;
    }

    /** The connection an object's own field holds, by type. */
    private static Connection connectionIn(Object holder) {
        if (holder == null) return null;
        for (Class<?> type = holder.getClass();
                type != null && !isJdk(type);
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != Connection.class) continue;
                field.setAccessible(true);
                try {
                    return (Connection) field.get(holder);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "cannot read " + field + " to find the connection", e);
                }
            }
        }
        return null;
    }

    @GrugGenerated(
            "the Alpha channel glue of #1040: no test the coverage gate counts can reach it, since"
                    + " only the multiplayer job runs it")
    private static final class AlphaLink implements GrugControlServer.Link {
        @Override
        public boolean sendToStarter(Object starter, byte[] message) {
            // A dead connection takes a packet into its queue without a word, so the starter is
            // asked about instead: a player who left is no longer in the world.
            PlayerEntity player = PLAYERS.get((Connection) starter);
            World world = GrugServerRun.world();
            if (player == null
                    || world == null
                    || !new ArrayList<>(world.players).contains(player)) {
                return false;
            }
            ((Connection) starter).send(new GrugControlPacket(message));
            return true;
        }

        @Override
        public void flushWorld() {
            // The server sends queued block changes from its player manager's tick, after the
            // world's, which a frozen test's tick never reaches; the same tick sends them here
            // (#1123). A held saved-data change goes after them, so a block placed in this tick is
            // on the client before the data its frame pass reads (#1191).
            GrugServerRun.flushBlockChanges();
            GrugSavedDataSync.flush(GrugServerRun.world());
        }

        /**
         * Reads the packets the starter's client sent and the frozen server thread has not read
         * yet, which is where a forwarded click's packet is (#1094). Alpha's connection is only
         * read from its own tick, a frozen one never reaches, so the caller runs this before the
         * runner tick.
         */
        @Override
        public void applyClientWork() {
            Object starter = server().starter();
            if (starter instanceof Connection) {
                ((Connection) starter).tick();
            }
        }

        /**
         * Any player who has finished login may start a run (first start wins in core); a
         * connection whose player was not found has not finished login. Operator-only starting is a
         * possible later hardening.
         */
        @Override
        public boolean mayStartRun(Object sender) {
            return PLAYERS.get((Connection) sender) != null;
        }
    }

    private static boolean onServer() {
        return GrugCore.getAdapter() != null
                && GrugCore.getAdapter().getSide() == GrugSide.DEDICATED_SERVER;
    }

    /**
     * Called on the reader thread with a message just read. An answer on a server is handed to the
     * waiting call here, because the game thread that would handle it is the one frozen, and a give
     * joins the queue the server thread drains, so neither waits for a game tick to reach the queue
     * (#1280). Answers true when the message was handled and the game thread must not, which a give
     * that arrived before the start of its own connection was handled is not: left to the game
     * thread, the give is read after the start it followed, in the order they came (#1288).
     */
    static boolean onRead(byte[] bytes) {
        if (!onServer() || bytes.length == 0) return false;
        GrugControlServer control = server();
        if (!control.handlesOnRead(bytes[0])) {
            return false;
        }
        // The reader thread of a Connection is a Thread subclass that holds it, so the sender of
        // this message is the connection it was read from, which the server compares with the
        // starter's.
        // A null sender (no connection found) is passed on: the server fails an answer it cannot
        // attribute and drops anything else.
        control.onMessage(findConnection(Thread.currentThread(), 0), bytes);
        return true;
    }

    /** Called on the game thread for every other message, from {@code Connection.tick}. */
    static void onHandle(PacketHandler handler, byte[] bytes) {
        if (onServer()) {
            Connection connection = findConnection(handler, 1);
            PlayerEntity player = playerOf(handler);
            if (connection != null && player != null) {
                PLAYERS.put(connection, player);
            }
            server().onMessage(connection, bytes);
        } else {
            GrugControlAlphaClient.onMessage(bytes);
        }
    }

    /**
     * The connection a handler holds, found by type so it does not depend on field names. The walk
     * stops at the JDK's own classes (a reader thread is a {@code Thread}), whose fields are not
     * ours to open.
     */
    static Connection findConnection(Object holder, int depth) {
        if (holder == null) return null;
        for (Class<?> type = holder.getClass(); !isJdk(type); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    if (field.getType() == Connection.class) {
                        return (Connection) field.get(holder);
                    }
                    if (depth > 0
                            && field.getType()
                                    .getName()
                                    .equals(
                                            "net.minecraft.client.network.handler"
                                                    + ".ClientNetworkHandler")) {
                        Connection nested = findConnection(field.get(holder), depth - 1);
                        if (nested != null) return nested;
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "cannot read " + field + " to find the connection", e);
                }
            }
        }
        return null;
    }

    private static boolean isJdk(Class<?> type) {
        return type == null || type.getName().startsWith("java.");
    }

    /**
     * The player a server-side handler belongs to: a field of the handler that holds one, or else
     * the player in the world that holds this handler. Null before the player is in the world.
     */
    public static PlayerEntity playerOf(PacketHandler handler) {
        try {
            for (Class<?> type = handler.getClass(); !isJdk(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (PlayerEntity.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object value = field.get(handler);
                        if (value != null) return (PlayerEntity) value;
                    }
                }
            }
            World world = GrugServerRun.world();
            if (world == null) return null;
            for (Object candidate : new ArrayList<>(world.players)) {
                for (Class<?> type = candidate.getClass();
                        !isJdk(type);
                        type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        field.setAccessible(true);
                        if (field.get(candidate) == handler) return (PlayerEntity) candidate;
                    }
                }
            }
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read the handler's player", e);
        }
    }

    /**
     * The connection a packet handler holds, which is where a GUI click's answer goes (#1098). A
     * server's handler keeps its connection as a field, so no world lookup is needed.
     */
    public static Connection connectionOf(PacketHandler handler) {
        return findConnection(handler, 0);
    }
}
