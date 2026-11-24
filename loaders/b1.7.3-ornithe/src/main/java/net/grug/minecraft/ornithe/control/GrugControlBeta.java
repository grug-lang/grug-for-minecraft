package net.grug.minecraft.ornithe.control;

import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugHandlerLookup;
import net.grug.minecraft.core.GrugLinkArms;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Beta half of the test-control channel (#1040, option A): the server side that forwards a
 * client-only call and waits for the answer with the world frozen. All decisions live in core's
 * {@link GrugControlServer}. The client side is {@link GrugControlBetaClient}, which a dedicated
 * server never loads.
 *
 * <p>This class names no client class, so the dedicated server's adapter can build the forwarded
 * screen calls and camera from it (#1246).
 *
 * <p>The freeze is the server's game thread blocking inside {@code capture}. It does not tick, so
 * it does not call {@code Connection.tick}, whose counter of ticks without a packet is the only
 * timeout Beta's connection has (1200). The answer is read on the reader thread, as {@link
 * GrugControlPacket#read} says, so nothing drains the connection by hand.
 *
 * <p>The sender of an answer is the connection the reader thread belongs to, and core's server
 * accepts an answer only from the connection that started the run. Before a capture and before a
 * settle ask the server flushes the block changes its chunk maps queued, as 1.20.6 does, because
 * the server only sends them at the end of its own tick, which a frozen tick never reaches. Sound
 * and screen calls do not flush, because they depend on no world state.
 */
public final class GrugControlBeta {
    static final long DEFAULT_TIMEOUT_MILLIS = 20_000;

    private static GrugControlServer server;

    private GrugControlBeta() {}

    public static synchronized GrugControlServer server() {
        if (server == null) {
            server =
                    new GrugControlServer(
                            new BetaLink(),
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

    private static final class BetaLink implements GrugControlServer.Link {
        @Override
        public boolean sendToStarter(Object starter, byte[] message) {
            // A dead connection takes a packet into its queue without a word, so the starter is
            // asked about instead: a player who left is no longer in the world.
            PlayerEntity player = PLAYERS.get((Connection) starter);
            return GrugLinkArms.deliverIf(
                    GrugLinkArms.stillHere(
                            player, GrugServerRun.world(), world -> new ArrayList<>(world.players)),
                    (Connection) starter,
                    connection -> connection.send(new GrugControlPacket(message)));
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
         * yet, which is where a forwarded click's packet is (#1094). Beta's connection is only read
         * from its own tick, a frozen one never reaches, so the caller runs this between two runner
         * ticks.
         */
        @Override
        public void applyClientWork() {
            GrugLinkArms.applyTo(server().starter(), Connection.class, Connection::tick);
        }

        /**
         * Any player who has finished login may start a run (first start wins in core); a
         * connection whose player was not found has not finished login. Operator-only starting is a
         * possible later hardening.
         */
        @Override
        public boolean mayStartRun(Object sender) {
            return GrugLinkArms.known(PLAYERS.get((Connection) sender));
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
            GrugLinkArms.deliver(
                    PLAYERS.get((Connection) sender),
                    player -> player.addMessage("\u00a7c" + message));
        }

        /**
         * A message the game thread's own {@code onHandle} handles is already on the server thread,
         * so the task runs at once. One read on a connection's reader thread is not: it is left for
         * the drain the runner makes before its next tick, which core marks pending.
         */
        @Override
        public void runOnServerThread(Runnable task) {
            GrugLinkArms.runIf(ON_GAME_THREAD.get(), task);
        }

        /** A CI server makes the player who starts a run an operator (#882); a real one never. */
        @Override
        public void onRunStarted(Object starter) {
            GrugLinkArms.runIfCi(
                    System::getenv,
                    () ->
                            GrugLinkArms.deliver(
                                    PLAYERS.get((Connection) starter),
                                    player -> GrugCore.getAdapter().makeOperator(player)));
        }
    }

    private static boolean onServer() {
        return GrugLinkArms.onDedicatedServer(GrugCore.getAdapter(), adapter -> adapter.getSide());
    }

    /**
     * Called on the reader thread with a message just read. An answer on a server is handed to the
     * waiting call there, because the game thread that would handle it is the one frozen, and a
     * give joins the queue the server thread drains, so neither waits for a game tick to reach the
     * queue (#1280). Core decides, and it leaves a give whose connection this loader has not learnt
     * to the game thread, which reads the start that came before it, in the order they came
     * (#1288), and learns the player it gives to there (#1418).
     */
    static boolean onRead(byte[] bytes) {
        // The reader thread of a Connection is a Thread subclass that holds it, so the sender of
        // this message is the connection it was read from, which the server compares with the
        // starter's and which its player is found by.
        return GrugLinkArms.ifHere(
                onServer(),
                () -> server().onRead(findConnection(Thread.currentThread(), 0), bytes));
    }

    /** Called on the game thread for every other message, from {@code Connection.tick}. */
    static void onHandle(PacketHandler handler, byte[] bytes) {
        GrugLinkArms.chooseWith(
                onServer(),
                bytes,
                message -> handleOnServer(handler, message),
                GrugControlBeta::onClientMessage);
    }

    /**
     * The client's arm of {@link #onHandle}. It is a method of this class, and not a method
     * reference to {@link GrugControlBetaClient}, because a method reference loads its target class
     * when the call site links, which a dedicated server must not do with a client class (#1246).
     */
    @GrugGenerated(
            "the client arm: a dedicated server never runs a client, and the client's own run is"
                    + " measured with the client classes")
    private static void onClientMessage(byte[] bytes) {
        GrugControlBetaClient.onMessage(bytes);
    }

    private static void handleOnServer(PacketHandler handler, byte[] bytes) {
        Connection connection = findConnection(handler, 1);
        GrugLinkArms.learn(PLAYERS, connection, playerOf(handler));
        ON_GAME_THREAD.set(Boolean.TRUE);
        try {
            server().onMessage(connection, bytes);
        } finally {
            ON_GAME_THREAD.set(Boolean.FALSE);
        }
    }

    /**
     * The connection a handler holds, found by type so it does not depend on field names. The walk
     * stops at the JDK's own classes (a reader thread is a {@code Thread}), whose fields are not
     * ours to open.
     */
    static Connection findConnection(Object holder, int depth) {
        return GrugHandlerLookup.connectionOf(
                holder,
                Connection.class,
                "net.minecraft.client.network.handler.ClientNetworkHandler",
                depth);
    }

    /**
     * The player a server-side handler belongs to: a field of the handler that holds one, or else
     * the player in the world that holds this handler. Null before the player is in the world.
     */
    private static PlayerEntity playerOf(PacketHandler handler) {
        World world = GrugServerRun.world();
        return GrugHandlerLookup.playerOf(
                handler, PlayerEntity.class, GrugLinkArms.membersOrNone(world, w -> w.players));
    }
}
