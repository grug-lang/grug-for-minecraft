package net.grug.minecraft.stationapi.control;

import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.stationapi.server.GrugServerRun;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.Connection;
import net.minecraft.network.NetworkHandler;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The StationAPI half of the test-control channel (#1040, option A): the server side that forwards
 * a client-only call and waits for the answer with the world frozen. All decisions live in core's
 * {@link GrugControlServer}. The client side is {@link GrugControlStationClient}, which a dedicated
 * server never loads.
 *
 * <p>This class names no client class, so the dedicated server's adapter can build the forwarded
 * screen calls and camera from it (#1246).
 *
 * <p>The freeze is the server's game thread blocking inside {@code capture}. It does not tick, so
 * it does not call {@code Connection.tick}, whose counter of ticks without a packet is the only
 * timeout this loader's connection has (1200). The answer is read on the reader thread, as {@link
 * GrugControlPacket#read} says, so nothing drains the connection by hand.
 *
 * <p>The sender of an answer is the connection the reader thread belongs to, and core's server
 * accepts an answer only from the connection that started the run. Before a capture and before a
 * settle ask the server flushes the block changes its chunk maps queued, as 1.20.6 does, because
 * the server only sends them at the end of its own tick, which a frozen tick never reaches. Sound
 * and screen calls do not flush, because they depend on no world state.
 */
@GrugGenerated(
        "the StationAPI channel glue of #1040: no test the coverage gate counts can reach it, since"
                + " only the multiplayer job runs it; every decision in it lives in core's"
                + " GrugControlServer and GrugControlClient")
public final class GrugControlStation {
    static final long DEFAULT_TIMEOUT_MILLIS = 20_000;

    private static GrugControlServer server;

    private GrugControlStation() {}

    public static synchronized GrugControlServer server() {
        if (server == null) {
            server =
                    new GrugControlServer(
                            new StationLink(),
                            GrugControlFaults.intFromEnv(
                                    "GRUG_CI_CONTROL_TIMEOUT_MILLIS",
                                    (int) DEFAULT_TIMEOUT_MILLIS));
        }
        return server;
    }

    /** The player each connection belongs to, learnt when its first control message is handled. */
    private static final Map<Connection, PlayerEntity> PLAYERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Whether this thread is inside {@link #onHandle}, which the game thread runs. */
    private static final ThreadLocal<Boolean> ON_GAME_THREAD =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The player who started the run, or null when none has or they have left. */
    public static PlayerEntity starterPlayer() {
        Object starter = server().starter();
        return starter == null ? null : PLAYERS.get((Connection) starter);
    }

    @GrugGenerated(
            "the StationAPI channel glue of #1040: no test the coverage gate counts can reach it,"
                    + " since only the multiplayer job runs it")
    private static final class StationLink implements GrugControlServer.Link {
        @GrugGenerated(
                "a race arm: the starter leaves between the server's check and its send, so the"
                        + " connection is dead or the player gone; no test can time a disconnect"
                        + " to land here")
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
            ((Connection) starter).sendPacket(new GrugControlPacket(message));
            return true;
        }

        @Override
        public void flushWorld() {
            // A frozen test's tick never reaches the server's own player manager, which is what
            // sends queued block changes; the same tick sends them here (#1123). A held
            // saved-data change goes after them, so a block placed in this tick is on the client
            // before the data its frame pass reads (#1191).
            GrugServerRun.flushBlockChanges();
            GrugSavedDataSync.flush(GrugServerRun.world());
        }

        /**
         * Reads the packets the starter's client sent and the frozen server thread has not read
         * yet, which is where a forwarded click's packet is (#1094). StationAPI's connection is
         * only read from its own tick, a frozen one never reaches, so the caller runs this between
         * two runner ticks.
         */
        @GrugGenerated(
                "the client drain: it runs only between two runner ticks of a dedicated server"
                        + " that has a client joined, which the multiplayer job alone launches")
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
        @GrugGenerated(
                "a race arm: a start that arrives before its connection has finished login finds"
                        + " no player; no test can time a message to land before the login ends")
        @Override
        public boolean mayStartRun(Object sender) {
            return PLAYERS.get((Connection) sender) != null;
        }

        /** This loader gives to the player who asked, after the operator check (#882). */
        @Override
        public boolean givesToSender() {
            return true;
        }

        @Override
        public Object playerOfSender(Object sender) {
            return PLAYERS.get((Connection) sender);
        }

        /** Shows the player the error in red chat, the way the game shows a refused command. */
        @Override
        public void tell(Object sender, String message) {
            PlayerEntity player = PLAYERS.get((Connection) sender);
            if (player != null) {
                player.sendMessage("\u00a7c" + message);
            }
        }

        /**
         * A message the game thread's own {@code onHandle} handles is already on the server thread,
         * so the task runs at once. One read on a connection's reader thread is not: it is left for
         * the drain the runner makes before its next tick, which core marks pending.
         */
        @Override
        public void runOnServerThread(Runnable task) {
            if (ON_GAME_THREAD.get()) {
                task.run();
            }
        }

        /** A CI server makes the player who starts a run an operator (#882); a real one never. */
        @Override
        public void onRunStarted(Object starter) {
            if (!"true".equals(System.getenv("GRUG_CI"))) return;
            PlayerEntity player = PLAYERS.get((Connection) starter);
            if (player != null) {
                GrugCore.getAdapter().makeOperator(player);
            }
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
     * (#1280). Core decides, and it leaves a give whose connection this loader has not learnt to
     * the game thread, which reads the start that came before it, in the order they came (#1288),
     * and learns the player it gives to there (#1418).
     */
    static boolean onRead(byte[] bytes) {
        if (!onServer()) return false;
        // The reader thread of a Connection is a Thread subclass that holds it, so the sender of
        // this message is the connection it was read from, which the server compares with the
        // starter's and which its player is found by.
        Connection sender = findConnection(Thread.currentThread(), 0);
        return server().onRead(sender, bytes);
    }

    /** Called on the game thread for every other message, from {@code Connection.tick}. */
    static void onHandle(NetworkHandler handler, byte[] bytes) {
        if (onServer()) {
            Connection connection = findConnection(handler, 1);
            PlayerEntity player = playerOf(handler);
            if (connection != null && player != null) {
                PLAYERS.put(connection, player);
            }
            ON_GAME_THREAD.set(Boolean.TRUE);
            try {
                server().onMessage(connection, bytes);
            } finally {
                ON_GAME_THREAD.set(Boolean.FALSE);
            }
        } else {
            GrugControlStationClient.onMessage(bytes);
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
                                    .equals("net.minecraft.client.network.ClientNetworkHandler")) {
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
    private static PlayerEntity playerOf(NetworkHandler handler) {
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
}
