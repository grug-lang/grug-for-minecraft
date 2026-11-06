package net.grug.minecraft.stationapi.control;

import net.grug.minecraft.core.GrugControlClient;
import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugControlWire;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugQuietCount;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.stationapi.GrugClientHooks;
import net.grug.minecraft.stationapi.StationApiAdapter;
import net.grug.minecraft.stationapi.server.GrugServerRun;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.Connection;
import net.minecraft.network.NetworkHandler;
import net.minecraft.world.World;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The StationAPI half of the test-control channel (#1040, option A): the server side that forwards
 * a client-only call and waits for the answer with the world frozen, and the client side that obeys
 * only during a run its own player started. All decisions live in core's {@link GrugControlServer}
 * and {@link GrugControlClient}.
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

    /** Client ticks to wait for the frames that show what the server sent, at the least. */
    static final int MIN_TICKS = 60;

    /**
     * Client ticks after which the frame the client has is captured even if not settled: 15 s at 20
     * ticks a second, well inside the server's 20 s wait, so the answer is back in time.
     */
    static final int MAX_TICKS = 300;

    private static GrugControlServer server;
    private static GrugControlClient client;
    private static boolean started = false;

    private static Vec3 eye;
    private static int x1;
    private static int y1;
    private static int x2;
    private static int y2;
    private static Consumer<BufferedImage> done;
    private static Consumer<String> failed;
    private static int waited;
    private static boolean pending = false;

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

    /** The player who started the run, or null when none has or they have left. */
    public static PlayerEntity starterPlayer() {
        Object starter = server().starter();
        return starter == null ? null : PLAYERS.get((Connection) starter);
    }

    @GrugGenerated(
            "the StationAPI channel glue of #1040: no test the coverage gate counts can reach it,"
                    + " since only the multiplayer job runs it")
    private static final class StationLink implements GrugControlServer.Link {
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
            GrugServerRun.flushBlockChanges();
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

    private static synchronized GrugControlClient client() {
        if (client == null) {
            client =
                    new GrugControlClient(
                            message -> {
                                Connection connection = clientConnection();
                                if (connection == null) {
                                    throw new IllegalStateException(
                                            "the control channel has an answer to send but the"
                                                    + " client has no connection to the server");
                                }
                                connection.sendPacket(new GrugControlPacket(message));
                            },
                            GrugControlStation::capture,
                            GrugControlClient.adapterCaller(GrugCore::getAdapter),
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_DROP_WIDTH", -1),
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_LATE_WIDTH", -1));
        }
        return client;
    }

    private static boolean onServer() {
        return GrugCore.getAdapter() != null
                && GrugCore.getAdapter().getSide() == GrugSide.DEDICATED_SERVER;
    }

    /**
     * Called on the reader thread with a message just read. An answer on a server is handed to the
     * waiting capture here, because the game thread that would handle it is the one frozen. Answers
     * true when the message was handled and the game thread must not.
     */
    static boolean onRead(byte[] bytes) {
        if (!onServer() || bytes.length == 0) return false;
        if (!GrugControlWire.isAnswer(bytes[0])) {
            return false;
        }
        // The reader thread of a Connection is a Thread subclass that holds it, so the sender of
        // this answer is the connection it was read from, which the server compares with the
        // starter's.
        // A null sender (no connection found) is passed on: the server fails what waits on it.
        server().onMessage(findConnection(Thread.currentThread(), 0), bytes);
        return true;
    }

    /** Called on the game thread for every other message, from {@code Connection.tick}. */
    static void onHandle(NetworkHandler handler, byte[] bytes) {
        if (onServer()) {
            Connection connection = findConnection(handler, 1);
            PlayerEntity player = playerOf(handler);
            if (connection != null && player != null) {
                PLAYERS.put(connection, player);
            }
            server().onMessage(connection, bytes);
        } else {
            client().onMessage(bytes);
        }
    }

    /**
     * The connection a handler holds, found by type so it does not depend on field names. The walk
     * stops at the JDK's own classes (a reader thread is a {@code Thread}), whose fields are not
     * ours to open.
     */
    private static Connection findConnection(Object holder, int depth) {
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

    private static Minecraft clientMinecraft;

    private static Connection clientConnection() {
        return findConnection(clientMinecraft == null ? null : clientMinecraft.world, 1);
    }

    private static void capture(
            Vec3 at,
            int left,
            int top,
            int right,
            int bottom,
            Consumer<BufferedImage> onDone,
            Consumer<String> onFailed) {
        eye = at;
        x1 = left;
        y1 = top;
        x2 = right;
        y2 = bottom;
        done = onDone;
        failed = onFailed;
        waited = 0;
        pending = true;
    }

    /**
     * The client starts its run only once the chunks around its player have stopped arriving: the
     * count of loaded chunks in a square of {@link #SETTLE_RADIUS} chunks has held for {@link
     * #SETTLE_QUIET_TICKS} ticks, with the 3x3 around the player at least. The server freezes at
     * the first forwarded call, so a run that started while chunks were half sent left the client's
     * recursive light update running off its stack (#1040). The server keeps ticking and sending
     * until the client's start arrives, so the count reaches the end of what it will send.
     */
    static final int SETTLE_RADIUS = 10;

    static final int SETTLE_QUIET_TICKS = 60;

    private static final GrugQuietCount chunksQuiet = new GrugQuietCount(SETTLE_QUIET_TICKS, 9);

    private static int loadedChunksAround(Minecraft mc) {
        int centreX = (int) Math.floor(mc.player.x) >> 4;
        int centreZ = (int) Math.floor(mc.player.z) >> 4;
        int loaded = 0;
        for (int dx = -SETTLE_RADIUS; dx <= SETTLE_RADIUS; dx++) {
            for (int dz = -SETTLE_RADIUS; dz <= SETTLE_RADIUS; dz++) {
                if (mc.world.isPosLoaded((centreX + dx) * 16 + 8, 64, (centreZ + dz) * 16 + 8)) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /**
     * One client tick. A client launched with GRUG_CI_CONNECT starts its run once it is in the
     * world and the chunks around it have settled (the stand-in for the operator's hotkey), and a
     * pending capture is taken once the client has had the frames that show what the server sent.
     */
    public static void onClientTick(Minecraft mc, GrugClientHooks hooks) {
        clientMinecraft = mc;
        if (!started
                && System.getenv("GRUG_CI_CONNECT") != null
                && mc.world != null
                && mc.player != null
                && mc.isWorldRemote()
                && GrugCore.getAdapter().isWorldReady(mc.player)
                && chunksQuiet.tick(loadedChunksAround(mc))) {
            started = true;
            hooks.beginControlRun();
            client().beginRun();
            System.out.println("[GRUG CONTROL] this client started a run, and sent the start");
        }
        if (started && mc.world == null) {
            // Left the server: a run belongs to the connection that started it.
            pending = false;
            client().endRun();
            started = false;
            hooks.endControlRun();
        }
        if (!pending) return;
        waited++;
        if (waited == 1 && mc.currentScreen != null) {
            // The runner shows no screen between tests, so the forwarded capture does the same.
            mc.setScreen(null);
        }
        if (waited == 1 || waited % 100 == 0) {
            System.out.println(
                    "[GRUG CONTROL] a capture is pending, client tick "
                            + waited
                            + " on "
                            + Thread.currentThread().getName());
        }
        boolean settled = GrugCore.getAdapter().isWorldSettled();
        // The frame has to show the world the server has and be drawn from the place the test's
        // room was built around, so a capture names the eye and the client waits for its own camera
        // to reach it (#1161).
        boolean cameraHere = GrugTestCamera.cameraAt(GrugCore.getAdapter().testCamera(), eye);
        if (GrugTestCamera.waitsForCapture(waited, MIN_TICKS, MAX_TICKS, settled, cameraHere))
            return;
        pending = false;
        if (mc.displayWidth != GrugScreenshots.WIDTH
                || mc.displayHeight != GrugScreenshots.HEIGHT) {
            failed.accept(
                    "the window is "
                            + mc.displayWidth
                            + "x"
                            + mc.displayHeight
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return;
        }
        String bad = GrugScreenshots.rectangleProblem(x1, y1, x2, y2);
        if (bad != null) {
            failed.accept(bad);
            return;
        }
        System.out.println(
                "[GRUG CONTROL] capturing after "
                        + waited
                        + " client ticks (world settled: "
                        + settled
                        + ", camera at the room's eye: "
                        + cameraHere
                        + ")");
        done.accept(
                ((StationApiAdapter) GrugCore.getAdapter()).captureForControl(x1, y1, x2, y2, mc));
    }
}
