package net.grug.minecraft.forge125.control;

import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugControlWire;
import net.grug.minecraft.forge125.GrugServerRun;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.EntityPlayerMP;
import net.minecraft.src.NetHandler;
import net.minecraft.src.NetworkManager;

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
 * to deliver it. The sender of an answer is the connection the reader thread belongs to, and core
 * accepts an answer only from the connection that started the run.
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
    private static final Map<NetworkManager, EntityPlayerMP> PLAYERS =
            Collections.synchronizedMap(new WeakHashMap<NetworkManager, EntityPlayerMP>());

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
        return starter == null ? null : PLAYERS.get((NetworkManager) starter);
    }

    @GrugGenerated(
            "the 1.2.5 channel glue of #1040: no test the coverage gate counts can reach it, since"
                    + " only the multiplayer job runs it")
    private static final class Link implements GrugControlServer.Link {
        @Override
        public boolean sendToStarter(Object starter, byte[] message) {
            // A dead connection takes a packet into its queue without a word, so the starter is
            // asked about instead: a player who left is no longer in the player list.
            EntityPlayerMP player = PLAYERS.get((NetworkManager) starter);
            if (player == null || !GrugServerRun.isConnected(player)) {
                return false;
            }
            player.playerNetServerHandler.sendPacket(new GrugControlPacket(message));
            return true;
        }

        @Override
        public void flushWorld() {
            GrugServerRun.flushBlockChanges();
        }

        /**
         * Any player who has finished login may start a run (first start wins in core); a
         * connection whose player was not found has not finished login.
         */
        @Override
        public boolean mayStartRun(Object sender) {
            return PLAYERS.get((NetworkManager) sender) != null;
        }
    }

    @Override
    public boolean onRead(Thread reader, byte[] bytes) {
        if (bytes.length == 0) return false;
        byte type = bytes[0];
        if (type != GrugControlWire.ANSWER_ERROR && type != GrugControlWire.ANSWER_CHUNK) {
            return false;
        }
        // An answer goes to the waiting capture from here, because the game thread that would
        // handle it is the one frozen. The reader thread holds the connection it reads, which core
        // compares with the starter's; a null sender is passed on and fails what waits on it.
        server().onMessage(GrugControlFind.byType(reader, NetworkManager.class), bytes);
        return true;
    }

    @Override
    public void onHandle(NetHandler handler, byte[] bytes) {
        NetworkManager connection = GrugControlFind.byType(handler, NetworkManager.class);
        EntityPlayerMP player = GrugControlFind.byType(handler, EntityPlayerMP.class);
        if (connection != null && player != null) {
            PLAYERS.put(connection, player);
        }
        server().onMessage(connection, bytes);
    }
}
