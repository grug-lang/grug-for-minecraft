package net.grug.minecraft.forge125.control;

import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugLinkArms;
import net.grug.minecraft.forge125.GrugServerRun;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.EntityPlayerMP;
import net.minecraft.src.NetHandler;
import net.minecraft.src.NetworkManager;
import net.minecraft.src.Packet3Chat;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The 1.2.5 dedicated server's half of the test-control channel (#1040, option A): forwards a
 * client-only call to the player who started the run and waits for the answer with the world
 * frozen. All decisions live in core's {@link GrugControlServer}.
 *
 * <p>The freeze is the server's game thread blocking inside {@code capture}. The answer is read on
 * the connection's reader thread (see {@link GrugControlPacket}), so nothing needs the game thread
 * to deliver it, and a give the client asks for is queued from there for the server thread's next
 * drain. The sender of a message is the connection the reader thread belongs to, and core accepts
 * an answer or a give only from the connection that started the run.
 */
@GrugGenerated(
        "the 1.2.5 channel glue of #1040: no test the coverage gate counts can reach it, since only"
                + " the multiplayer job runs it; every decision in it lives in core's"
                + " GrugControlServer")
public final class GrugControlServer125 implements GrugControlRouter {
    /**
     * The wait for an answer: under the 30 s of silence after which a client's socket read times
     * out, and long enough for a software-rendered client that is still loading chunks.
     */
    static final long DEFAULT_TIMEOUT_MILLIS = 20_000;

    private static GrugControlServer server;

    /** The player each connection belongs to, learnt when its first control message is handled. */
    private static final Map<NetworkManager, WeakReference<EntityPlayerMP>> PLAYERS =
            Collections.synchronizedMap(
                    new WeakHashMap<NetworkManager, WeakReference<EntityPlayerMP>>());

    /**
     * The player of a connection, or null. The map holds the player weakly: a player holds its
     * connection, so a strong value would keep the map's weak key alive for the process's life.
     */
    private static EntityPlayerMP playerOf(NetworkManager connection) {
        WeakReference<EntityPlayerMP> ref = PLAYERS.get(connection);
        return ref == null ? null : ref.get();
    }

    /** Whether this thread is inside {@link #onHandle}, which the game thread runs. */
    private static final ThreadLocal<Boolean> ON_GAME_THREAD = new ThreadLocal<Boolean>();

    private GrugControlServer125() {}

    /** Installs this half as the packet's handler. Called by {@code GrugServerRun.install}. */
    public static void install() {
        GrugControlPacket.router = new GrugControlServer125();
    }

    public static synchronized GrugControlServer server() {
        if (server == null) {
            server =
                    new GrugControlServer(
                            new Link(),
                            GrugControlFaults.intFromEnv(
                                    "GRUG_CI_CONTROL_TIMEOUT_MILLIS",
                                    (int) DEFAULT_TIMEOUT_MILLIS));
        }
        return server;
    }

    /** The player who started the run, or null when none has or they have left. */
    public static EntityPlayerMP starterPlayer() {
        Object starter = server().starter();
        return starter == null ? null : playerOf((NetworkManager) starter);
    }

    @GrugGenerated(
            "the 1.2.5 channel glue of #1040: no test the coverage gate counts can reach it, since"
                    + " only the multiplayer job runs it")
    private static final class Link implements GrugControlServer.Link {
        @Override
        public boolean sendToStarter(Object starter, byte[] message) {
            // A dead connection takes a packet into its queue without a word, so the starter is
            // asked about instead: a player who left is no longer in the player list.
            EntityPlayerMP player = playerOf((NetworkManager) starter);
            return GrugLinkArms.deliverIf(
                    GrugLinkArms.stillConnected(player, GrugServerRun::isConnected),
                    player,
                    found ->
                            found.playerNetServerHandler.sendPacket(
                                    new GrugControlPacket(message)));
        }

        @Override
        public void flushWorld() {
            GrugServerRun.flushBlockChanges();
        }

        /**
         * Reads the packets the starter's client sent and the frozen server thread has not read
         * yet, which is where a forwarded click's packet is (#1094). The game's own tick calls this
         * through the player's handler once per server tick; a test ticks inside that tick, so
         * without this its assertion would run before the click.
         */
        @Override
        public void applyClientWork() {
            Object starter = GrugControlServer125.server().starter();
            if (starter instanceof NetworkManager) {
                ((NetworkManager) starter).processReadPackets();
            }
        }

        /** This loader gives to the player who asked, after the operator check (#882). */
        @Override
        public boolean givesToSender() {
            return true;
        }

        @Override
        public Object playerOfSender(Object sender) {
            return playerOf((NetworkManager) sender);
        }

        /** Shows the player the error in red chat, the way the game shows a refused command. */
        @Override
        public void tell(Object sender, String message) {
            EntityPlayerMP player = playerOf((NetworkManager) sender);
            if (player != null) {
                player.playerNetServerHandler.sendPacket(new Packet3Chat("\u00a7c" + message));
            }
        }

        /**
         * A message handled by the game thread's own {@code onHandle} is already on the server
         * thread, so the task runs at once. One read on a connection's reader thread is not: it is
         * left for the drain the runner makes before its next tick, which core marks pending.
         */
        @Override
        public void runOnServerThread(Runnable task) {
            if (Boolean.TRUE.equals(ON_GAME_THREAD.get())) {
                task.run();
            }
        }

        /** A CI server makes the player who starts a run an operator (#882); a real one never. */
        @Override
        public void onRunStarted(Object starter) {
            if (!"true".equals(System.getenv("GRUG_CI"))) return;
            EntityPlayerMP player = playerOf((NetworkManager) starter);
            if (player != null) {
                GrugCore.getAdapter().makeOperator(player);
            }
        }

        /**
         * Any player who has finished login may send a start, and a connection whose player was not
         * found has not; core then judges it and accepts only an operator's (#37), the first
         * accepted one winning.
         */
        @Override
        public boolean mayStartRun(Object sender) {
            return playerOf((NetworkManager) sender) != null;
        }
    }

    @Override
    public boolean onRead(Thread reader, byte[] bytes) {
        // The reader thread holds the connection it reads, which core compares with the starter's
        // and which this loader's player is found by. An answer and a give go in from there,
        // because the game thread that would handle them is the one frozen: the answer completes
        // what waits on it, and the give only joins the queue that thread drains, so neither waits
        // for a game tick to reach the queue (#1280). Core leaves a give whose connection this
        // loader has not learnt to the game thread, which reads it after that start, in the order
        // they came (#1288), and learns the player it gives to there (#1418).
        return server().onRead(GrugControlFind.byType(reader, NetworkManager.class), bytes);
    }

    @Override
    public void onHandle(NetHandler handler, byte[] bytes) {
        NetworkManager connection = GrugControlFind.byType(handler, NetworkManager.class);
        EntityPlayerMP player = GrugControlFind.byType(handler, EntityPlayerMP.class);
        if (connection != null && player != null) {
            PLAYERS.put(connection, new WeakReference<EntityPlayerMP>(player));
        }
        ON_GAME_THREAD.set(Boolean.TRUE);
        try {
            server().onMessage(connection, bytes);
        } finally {
            ON_GAME_THREAD.set(Boolean.FALSE);
        }
    }
}
