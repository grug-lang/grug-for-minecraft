package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugControlClient;
import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.network.CustomPayloadEvent;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * The client half of the test-control channel on 1.20.6 (#1040, option A): obeys a forwarded
 * screenshot request, but only while a run that this client's own player started is active, which
 * core's {@link GrugControlClient} decides.
 *
 * <p>A request arrives on the game thread (ordered after the block updates the server sent before
 * it) and is answered a few client ticks later, once the client has drawn frames that show those
 * updates and its renderer is idle. That wait is the client-side settle the singleplayer path does
 * with {@code Test.world_settled}; here the server cannot ask for it, because it is frozen.
 */
@GrugGenerated(
        "a joined client: no test the coverage gate counts can reach it, since only the multiplayer"
                + " job launches one")
public final class GrugControlClientForge {
    /** Client ticks to wait for the frames that show what the server sent, at the least. */
    static final int MIN_TICKS = 4;

    /** Client ticks after which the frame the client has is captured even if it is still busy. */
    static final int MAX_TICKS = 200;

    private static GrugControlClient client;

    private static int x1;
    private static int y1;
    private static int x2;
    private static int y2;
    private static Consumer<BufferedImage> done;
    private static Consumer<String> failed;
    private static int waited;
    private static boolean pendingCapture = false;

    private GrugControlClientForge() {}

    private static synchronized GrugControlClient client() {
        if (client == null) {
            client =
                    new GrugControlClient(
                            GrugControlForge::sendToServer,
                            GrugControlClientForge::capture,
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_DROP_WIDTH", -1),
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_LATE_WIDTH", -1),
                            GrugControlFaults.intFromEnv("GRUG_CI_CONTROL_LATE_MILLIS", 0));
        }
        return client;
    }

    /** The player started a run (here, the client joined a server named by GRUG_CI_CONNECT). */
    public static void beginRun() {
        client().beginRun();
    }

    /** A message from the server, on the network thread. */
    static void onServerMessage(byte[] bytes, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> client().onMessage(bytes));
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
        pendingCapture = true;
    }

    /** One client tick: takes the pending capture once the frame shows the server's world. */
    public static void onClientTick(Minecraft mc) {
        if (!pendingCapture) return;
        waited++;
        boolean idle = mc.level != null && ForgeAdapter.clientIdleForControl(mc);
        if (waited < MIN_TICKS || (!idle && waited < MAX_TICKS)) return;
        pendingCapture = false;
        if (mc.getWindow().getWidth() != GrugScreenshots.WIDTH
                || mc.getWindow().getHeight() != GrugScreenshots.HEIGHT) {
            failed.accept(
                    "the window is "
                            + mc.getWindow().getWidth()
                            + "x"
                            + mc.getWindow().getHeight()
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return;
        }
        if (x1 < 0
                || y1 < 0
                || x2 > GrugScreenshots.WIDTH
                || y2 > GrugScreenshots.HEIGHT
                || x2 <= x1
                || y2 <= y1) {
            failed.accept(
                    "the rectangle ("
                            + x1
                            + ","
                            + y1
                            + ","
                            + x2
                            + ","
                            + y2
                            + ") is empty or outside the "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT
                            + " frame");
            return;
        }
        System.out.println(
                "[GRUG CONTROL] capturing after "
                        + waited
                        + " client ticks (renderer idle: "
                        + idle
                        + ")");
        done.accept(ForgeAdapter.captureRectangle(x1, y1, x2, y2));
    }
}
