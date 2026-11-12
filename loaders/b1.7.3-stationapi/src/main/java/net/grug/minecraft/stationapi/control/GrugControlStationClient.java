package net.grug.minecraft.stationapi.control;

import net.grug.minecraft.core.GrugControlClient;
import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugQuietCount;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.stationapi.GrugClientHooks;
import net.grug.minecraft.stationapi.StationApiAdapter;
import net.grug.minecraft.stationapi.gui.GrugScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * The StationAPI client half of the test-control channel (#1040, option A): obeys only during a run
 * its own player started. All decisions live in core's {@link GrugControlClient}.
 *
 * <p>It is a class of its own because the client types it names must not be reachable from {@link
 * GrugControlStation}: a dedicated server builds that class when its adapter is constructed, and
 * resolving the client {@code Minecraft} from it failed the Alpha and Beta servers' boot (#1246).
 * Only a client loads this class, from {@link GrugControlStation#onHandle}'s client branch and from
 * the client's own tick.
 */
@GrugGenerated(
        "a joined client: no test the coverage gate counts can reach it, since only the multiplayer"
                + " job launches one")
public final class GrugControlStationClient {
    /**
     * Client ticks to wait for the frames that show what the server sent, at the least. The two
     * conditions in front of the capture already cover the world and the camera, so this is the
     * margin for the frames drawn after the change, and 4 is the floor 1.20.6 has run on every
     * multiplayer leg of every dispatch (#1199).
     */
    static final int MIN_TICKS = 4;

    /**
     * Client ticks after which the frame the client has is captured even if not settled: 15 s at 20
     * ticks a second, well inside the server's 20 s wait, so the answer is back in time.
     */
    static final int MAX_TICKS = 300;

    private static GrugControlClient client;
    private static boolean started = false;
    private static Minecraft clientMinecraft;

    private static Vec3 eye;
    private static int x1;
    private static int y1;
    private static int x2;
    private static int y2;
    private static Consumer<BufferedImage> done;
    private static Consumer<String> failed;
    private static int waited;
    private static boolean pending = false;

    private GrugControlStationClient() {}

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
                            GrugControlStationClient::capture,
                            GrugControlClient.adapterCaller(GrugCore::getAdapter),
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_DROP_WIDTH", -1),
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_LATE_WIDTH", -1));
        }
        return client;
    }

    /** A message from the server, on the game thread. */
    static void onMessage(byte[] bytes) {
        client().onMessage(bytes);
    }

    private static Connection clientConnection() {
        return GrugControlStation.findConnection(
                clientMinecraft == null ? null : clientMinecraft.world, 1);
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
        if (waited == 1 && mc.currentScreen != null && !(mc.currentScreen instanceof GrugScreen)) {
            // The runner shows no screen between tests, so the forwarded capture does the same.
            // The GUI a server opened for its player is the capture's subject, so it stays:
            // closing it would capture the world and close the server's screen handler with it
            // (#1098).
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
