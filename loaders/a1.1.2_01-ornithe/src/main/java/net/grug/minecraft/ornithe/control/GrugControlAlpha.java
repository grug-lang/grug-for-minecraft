package net.grug.minecraft.ornithe.control;

import net.grug.minecraft.core.GrugControlClient;
import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugControlWire;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugQuietCount;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.grug.minecraft.ornithe.OrnitheAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.function.Consumer;

/**
 * The Alpha half of the test-control channel (#1040, option A): the server side that forwards a
 * client-only call and waits for the answer with the world frozen, and the client side that obeys
 * only during a run its own player started. All decisions live in core's {@link GrugControlServer}
 * and {@link GrugControlClient}.
 *
 * <p>The freeze is the server's game thread blocking inside {@code capture}. It does not tick, so
 * it does not call {@code Connection.tick}, whose counter of ticks without a packet is the only
 * timeout Alpha's connection has (1200). The answer is read on the reader thread, as {@link
 * GrugControlPacket#read} says, so nothing drains the connection by hand.
 *
 * <p>The sender of an answer is the connection the reader thread belongs to, and core's server
 * accepts an answer only from the connection that started the run. The server does not flush block
 * changes before a request, the way 1.20.6 does, because Alpha's server queues them per chunk and a
 * test that waits a tick between placing and capturing has had them sent.
 */
@GrugGenerated(
        "the Alpha channel glue of #1040: no test the coverage gate counts can reach it, since only"
                + " the multiplayer job runs it; every decision in it lives in core's"
                + " GrugControlServer and GrugControlClient")
public final class GrugControlAlpha {
    static final long DEFAULT_TIMEOUT_MILLIS = 20_000;

    /** Client ticks to wait for the frames that show what the server sent, at the least. */
    static final int MIN_TICKS = 20;

    /** Client ticks after which the frame the client has is captured even if not settled. */
    static final int MAX_TICKS = 400;

    private static GrugControlServer server;
    private static GrugControlClient client;
    private static boolean started = false;

    private static int x1;
    private static int y1;
    private static int x2;
    private static int y2;
    private static Consumer<BufferedImage> done;
    private static Consumer<String> failed;
    private static int waited;
    private static boolean pending = false;

    private GrugControlAlpha() {}

    public static synchronized GrugControlServer server() {
        if (server == null) {
            server =
                    new GrugControlServer(
                            new GrugControlServer.Link() {
                                @Override
                                public boolean sendToStarter(Object starter, byte[] message) {
                                    ((Connection) starter).send(new GrugControlPacket(message));
                                    return true;
                                }

                                @Override
                                public void flushWorld() {
                                    System.out.println(
                                            "[GRUG CONTROL] no world flush on Alpha: the test"
                                                    + " waited a tick since it placed blocks");
                                }
                            },
                            GrugControlFaults.intFromEnv(
                                    "GRUG_CI_CONTROL_TIMEOUT_MILLIS",
                                    (int) DEFAULT_TIMEOUT_MILLIS));
        }
        return server;
    }

    private static synchronized GrugControlClient client() {
        if (client == null) {
            client =
                    new GrugControlClient(
                            message -> {
                                Connection connection = clientConnection();
                                if (connection != null) {
                                    connection.send(new GrugControlPacket(message));
                                }
                            },
                            GrugControlAlpha::capture,
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_DROP_WIDTH", -1),
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_LATE_WIDTH", -1),
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_LATE_MILLIS", 0));
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
        byte type = bytes[0];
        if (type != GrugControlWire.ANSWER_ERROR && type != GrugControlWire.ANSWER_CHUNK) {
            return false;
        }
        // The reader thread of a Connection is a Thread subclass that holds it, so the sender of
        // this answer is the connection it was read from, which the server compares with the
        // starter's.
        server().onMessage(findConnection(Thread.currentThread(), 0), bytes);
        return true;
    }

    /** Called on the game thread for every other message, from {@code Connection.tick}. */
    static void onHandle(PacketHandler handler, byte[] bytes) {
        if (onServer()) {
            server().onMessage(findConnection(handler, 1), bytes);
        } else {
            client().onMessage(bytes);
        }
    }

    /** The connection a handler holds, found by type so it does not depend on field names. */
    private static Connection findConnection(Object holder, int depth) {
        if (holder == null) return null;
        for (Class<?> type = holder.getClass(); type != Object.class; type = type.getSuperclass()) {
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
                } catch (IllegalAccessException | RuntimeException e) {
                    // Not this field.
                }
            }
        }
        return null;
    }

    private static Connection clientConnection() {
        return findConnection(
                net.ornithemc.osl.lifecycle.api.client.MinecraftInstance.get().world, 1);
    }

    private static void capture(
            int left,
            int top,
            int right,
            int bottom,
            Consumer<BufferedImage> onDone,
            Consumer<String> onFailed) {
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
                if (mc.world.isChunkLoaded((centreX + dx) * 16 + 8, 64, (centreZ + dz) * 16 + 8)) {
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
        if (!started
                && System.getenv("GRUG_CI_CONNECT") != null
                && mc.world != null
                && mc.player != null
                && mc.isMultiplayer()
                && GrugCore.getAdapter().isWorldReady(mc.player)
                && chunksQuiet.tick(loadedChunksAround(mc))) {
            started = true;
            hooks.beginControlRun();
            client().beginRun();
            System.out.println("[GRUG CONTROL] this client started a run, and sent the start");
        }
        if (!pending) return;
        waited++;
        if (waited == 1 || waited % 100 == 0) {
            System.out.println(
                    "[GRUG CONTROL] a capture is pending, client tick "
                            + waited
                            + " on "
                            + Thread.currentThread().getName());
        }
        if (waited < MIN_TICKS) return;
        boolean settled = GrugCore.getAdapter().isWorldSettled();
        if (!settled && waited < MAX_TICKS) return;
        pending = false;
        if (mc.width != GrugScreenshots.WIDTH || mc.height != GrugScreenshots.HEIGHT) {
            failed.accept(
                    "the window is "
                            + mc.width
                            + "x"
                            + mc.height
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
                        + ")");
        done.accept(((OrnitheAdapter) GrugCore.getAdapter()).captureForControl(x1, y1, x2, y2, mc));
    }
}
