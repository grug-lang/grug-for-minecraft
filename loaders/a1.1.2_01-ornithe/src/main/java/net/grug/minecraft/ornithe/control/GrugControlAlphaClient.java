package net.grug.minecraft.ornithe.control;

import net.grug.minecraft.core.GrugControlClient;
import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugQuietCount;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.grug.minecraft.ornithe.OrnitheAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.network.Connection;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.function.Consumer;

/**
 * The Alpha client half of the test-control channel (#1040, option A): obeys only during a run its
 * own player started. All decisions live in core's {@link GrugControlClient}.
 *
 * <p>It is a class of its own because the client types it names must not be reachable from {@link
 * GrugControlAlpha}: a dedicated server builds that class when its adapter is constructed, and
 * resolving the client {@code Minecraft} from it failed the server's boot (#1246). Only a client
 * loads this class, from {@link GrugControlAlpha#onHandle}'s client branch and from the client's
 * own tick.
 */
@GrugGenerated(
        "a joined client: no test the coverage gate counts can reach it, since only the multiplayer"
                + " job launches one")
public final class GrugControlAlphaClient {
    /** Client ticks to wait for the frames that show what the server sent, at the least. */
    static final int MIN_TICKS = 20;

    /** Client ticks after which the frame the client has is captured even if not settled. */
    static final int MAX_TICKS = 400;

    private static GrugControlClient client;

    /**
     * Whether this client has started its one run on the connection it is on. It stays set after
     * that run ends while the client is still in the same world, so the connection starts no second
     * run, and it is cleared when the client leaves so a fresh connection starts its own.
     */
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

    private GrugControlAlphaClient() {}

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
                                connection.send(new GrugControlPacket(message));
                            },
                            GrugControlAlphaClient::capture,
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
     * Holds the client's own player at the eye the capture named. The server cannot: it is frozen
     * inside the call that asked for the frame, so nothing there ticks to hold its player, and a
     * client that drifted from the eye drew the room from where it had drifted to (#1178). The
     * Alpha client's {@code y} is its eye, the same field its position packet carries and its own
     * move then rewrites from the shape, so the pin is a move to the eye itself, the call the
     * position packet handler makes with the packet's {@code minY}.
     *
     * <p>It runs at the head of the client tick, before the world's physics, and the frame a
     * capture reads is one the client drew after that tick. That frame is still drawn from the eye:
     * {@code GameRenderer} interpolates between {@code lastY} and {@code y}, {@code
     * updatePositionAndAngles} writes both and {@code setPosition} moves the shape with them, the
     * pin zeroes the velocity, and this version's {@code MobEntity.moveRelative} moves the player
     * by the velocity the previous tick ended with and only then applies gravity, so the physics
     * cannot carry a pinned player off the eye in the tick the pin ran in. See #1161 and #1193.
     */
    private static void pinAtCaptureEye(PlayerEntity player) {
        if (waited == 1 && (player.x != eye.x() || player.y != eye.y() || player.z != eye.z())) {
            // The measurement that fails without the pin: the request arrived with the client off
            // the eye. #1161's a1 dedicated leg logged 28 of 82 captures false against 0 on the
            // base, and forwarded_capture_late_then's two frames were identical, so neither was
            // drawn from the eye the room was built around.
            System.out.println(
                    "[GRUG CONTROL] the client's camera is at ("
                            + player.x
                            + ","
                            + player.y
                            + ","
                            + player.z
                            + "), not the room's eye ("
                            + eye.x()
                            + ","
                            + eye.y()
                            + ","
                            + eye.z()
                            + "); pinning it for the capture");
        }
        player.updatePositionAndAngles(eye.x(), eye.y(), eye.z(), 0.0F, 0.0F);
        player.velocityX = 0.0;
        player.velocityY = 0.0;
        player.velocityZ = 0.0;
    }

    /**
     * The GL viewport the frame was drawn into, as {@code x,y,width x height}.
     *
     * <p>It is the measurement #1213 needs for the joined capture that draws its world 2 px higher
     * than the flat golden. A capture's rectangle is converted from the window's top with {@code
     * Minecraft.height}, while the world is projected into whatever viewport GL holds, and a
     * perspective projection's centre is the viewport's middle: a viewport 4 px shorter than the
     * crop assumes would put the world's centre 2 px off the crop's, which is the measured shift.
     * Printing it names that rather than leaving it to be guessed from the picture.
     */
    private static String glViewport() {
        IntBuffer viewport = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        return viewport.get(0)
                + ","
                + viewport.get(1)
                + ","
                + viewport.get(2)
                + "x"
                + viewport.get(3);
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

    private static Connection clientConnection() {
        return GrugControlAlpha.findConnection(
                net.ornithemc.osl.lifecycle.api.client.MinecraftInstance.get().world, 1);
    }

    /**
     * The connection this client holds to its server, which a GUI click and close are sent on
     * (#1098). Null when this client is not connected, so a screen nothing can reach the server
     * from sends nothing.
     */
    public static Connection clientConnectionForScreens() {
        return clientConnection();
    }

    /**
     * The end of one client tick, after the world's physics: while a capture is pending, the
     * player's camera tilt is the tilt of a player standing still ({@link
     * GrugTestPlayerSnap#STANDING_TILT}), whatever the pinned velocity or the floor under it.
     *
     * <p>The pin zeroes the velocity, and the tilt {@code PlayerEntity.mobTick} computes from it is
     * therefore 0, but the flat goldens were drawn by a client-only player standing on a floor,
     * whose tilt of 0.235 degrees shifts the whole frame 2 px (#1213). The velocity cannot carry
     * that tilt, because a player given a falling velocity leaves the eye in the tick, so the tilt
     * is written here, after {@code mobTick} has run and before the frame is drawn. Both fields are
     * written because the camera interpolates from {@code lastTilt} to {@code tilt}. A player with
     * no floor gets the same tilt, so every pinned frame agrees with every other ({@code
     * forwarded_capture_pinned_eye}).
     */
    public static void afterClientTick(PlayerEntity player) {
        if (pending && eye != null && player != null) {
            player.lastTilt = GrugTestPlayerSnap.STANDING_TILT;
            player.tilt = GrugTestPlayerSnap.STANDING_TILT;
        }
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
            // A run this client started by hand owns the window and its particle switch until it
            // ends, so the joined run waits for it rather than sharing them (#1213).
            if (hooks.beginControlRun()) {
                started = true;
                client().beginRun();
                System.out.println("[GRUG CONTROL] this client started a run, and sent the start");
            }
        }
        if (started && (mc.world == null || !client().runActive())) {
            // The run is over: this client left the server it ran on, or the server ended the run
            // while the client stays in its world, which is a joined client's ordinary state
            // outside a run (#1040). Its window and particle switch go back to ordinary play
            // either way, and the gate stays set while the client is still in that world so the
            // same connection starts no second run. Both calls are idempotent on the ticks
            // after.
            pending = false;
            client().endRun();
            hooks.endControlRun();
            if (mc.world == null) {
                // A run belongs to the connection that started it, and a fresh connection starts
                // its own.
                started = false;
            }
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
        PlayerEntity player = mc.player;
        // Read before the pin: the pin writes this client's own eye, so a read after it reports
        // that write rather than where the client's camera was when the request arrived. This is
        // the count the capture log's "camera at the room's eye" now gives, the mirror of the
        // drift line the first pin prints (#1161).
        boolean cameraHere = GrugTestCamera.cameraAt(GrugCore.getAdapter().testCamera(), eye);
        if (eye != null && player != null) {
            pinAtCaptureEye(player);
        }
        boolean settled = GrugCore.getAdapter().isWorldSettled();
        // The frame has to show the world the server has and be drawn from the place the test's
        // room was built around, so a capture names the eye and the client waits for its own camera
        // to reach it (#1161).
        if (GrugTestCamera.waitsForCapture(waited, MIN_TICKS, MAX_TICKS, settled, cameraHere))
            return;
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
                        + ", camera at the room's eye: "
                        + cameraHere
                        + ", window "
                        + mc.width
                        + "x"
                        + mc.height
                        + ", display "
                        + Display.getWidth()
                        + "x"
                        + Display.getHeight()
                        + ", viewport "
                        + glViewport()
                        + ")");
        done.accept(((OrnitheAdapter) GrugCore.getAdapter()).captureForControl(x1, y1, x2, y2, mc));
    }
}
