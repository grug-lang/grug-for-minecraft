package net.grug.minecraft.forge125.control;

import net.grug.minecraft.core.GrugControlClient;
import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugQuietCount;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.forge125.Grug125ClientAdapter;
import net.grug.minecraft.forge125.client.GrugClientHooks;
import net.grug.minecraft.forge125.client.GrugEmptyScreen;
import net.grug.minecraft.forge125.client.GrugScreen;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.src.ModLoader;
import net.minecraft.src.NetClientHandler;
import net.minecraft.src.NetHandler;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * The 1.2.5 client's half of the test-control channel (#1040, option A): obeys only during a run
 * its own player started. All decisions live in core's {@link GrugControlClient}; this class holds
 * the game's side of them, the settle wait before the start and the wait for frames before a
 * capture. It is only loaded on a client.
 */
@GrugGenerated(
        "the 1.2.5 channel glue of #1040: no test the coverage gate counts can reach it, since only"
                + " the multiplayer job runs it; every decision in it lives in core's"
                + " GrugControlClient")
public final class GrugControl125Client implements GrugControlRouter {
    /** Client ticks to wait for the frames that show what the server sent, at the least. */
    static final int MIN_TICKS = 60;

    /**
     * Client ticks after which the frame the client has is captured even if not settled: 15 s at 20
     * ticks a second, well inside the server's 20 s wait, so the answer is back in time.
     */
    static final int MAX_TICKS = 300;

    /** The chunks counted around the player, and how long the count must hold (see Beta). */
    static final int SETTLE_RADIUS = 10;

    static final int SETTLE_QUIET_TICKS = 60;

    private static final GrugControlClient CLIENT =
            new GrugControlClient(
                    message -> sendQueue().addToSendQueue(new GrugControlPacket(message)),
                    GrugControl125Client::capture,
                    GrugControlClient.adapterCaller(GrugCore::getAdapter),
                    GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_DROP_WIDTH", -1),
                    GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_LATE_WIDTH", -1));

    private static final GrugQuietCount chunksQuiet = new GrugQuietCount(SETTLE_QUIET_TICKS, 9);

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

    private GrugControl125Client() {}

    /** Installs this half as the packet's handler. Called by {@code mod_Grug.load} on a client. */
    public static void install() {
        GrugControlPacket.router = new GrugControl125Client();
    }

    private static NetClientHandler sendQueue() {
        Minecraft mc = ModLoader.getMinecraftInstance();
        if (mc == null || mc.getSendQueue() == null) {
            throw new IllegalStateException(
                    "the control channel has an answer to send but the client has no connection to"
                            + " the server");
        }
        return mc.getSendQueue();
    }

    /** Messages arrive on the game thread: the client has nothing waiting on its reader thread. */
    @Override
    public boolean onRead(Thread reader, byte[] bytes) {
        return false;
    }

    @Override
    public void onHandle(NetHandler handler, byte[] bytes) {
        CLIENT.onMessage(bytes);
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

    private static int loadedChunksAround(Minecraft mc) {
        int centreX = (int) Math.floor(mc.thePlayer.posX) >> 4;
        int centreZ = (int) Math.floor(mc.thePlayer.posZ) >> 4;
        int loaded = 0;
        for (int dx = -SETTLE_RADIUS; dx <= SETTLE_RADIUS; dx++) {
            for (int dz = -SETTLE_RADIUS; dz <= SETTLE_RADIUS; dz++) {
                if (mc.theWorld.blockExists((centreX + dx) * 16 + 8, 64, (centreZ + dz) * 16 + 8)) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /**
     * One client game tick. A client launched with GRUG_CI_CONNECT starts its run once it is in the
     * world and the chunks around it have settled (the stand-in for the operator's hotkey), and a
     * pending capture is taken once the client has had the frames that show what the server sent.
     */
    public static void onClientTick(Minecraft mc, GrugClientHooks hooks) {
        if (!started
                && System.getenv("GRUG_CI_CONNECT") != null
                && mc.theWorld != null
                && mc.thePlayer != null
                && mc.isMultiplayerWorld()
                && GrugCore.getAdapter().isWorldReady(mc.thePlayer)
                && chunksQuiet.tick(loadedChunksAround(mc))) {
            started = true;
            hooks.beginControlRun();
            CLIENT.beginRun();
            System.out.println("[GRUG CONTROL] this client started a run, and sent the start");
        }
        if (started && mc.theWorld == null) {
            // Left the server: a run belongs to the connection that started it.
            pending = false;
            CLIENT.endRun();
            started = false;
            hooks.endControlRun();
        }
        if (!pending) return;
        waited++;
        if (waited == 1
                && !(mc.currentScreen instanceof GrugEmptyScreen)
                && !(mc.currentScreen instanceof GrugScreen)) {
            // A headless client is never the active window, so 1.2.5 puts a screen with a dirt
            // background over the world; the runner shows the empty screen between tests, so the
            // forwarded capture of the joined client's world does the same. The GUI a server opened
            // for its player is the capture's subject, so it stays: replacing it would capture the
            // world and close the server's container with it (#1098).
            mc.displayGuiScreen(new GrugEmptyScreen());
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
                ((Grug125ClientAdapter) GrugCore.getAdapter())
                        .captureForControl(x1, y1, x2, y2, mc));
    }
}
