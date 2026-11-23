package net.grug.minecraft.ornithe.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.network.packet.PlayerMovePacket;

/**
 * DIAGNOSTIC ONLY (branch diag-a1-drift, never merged): logs the joined client's player for 40
 * client ticks after each server position packet, the packet's application, the frame count, and
 * every position packet the client sends back that differs from the last pose the server set.
 */
public final class GrugDriftDiagClient {
    private static final int WINDOW = 40;
    private static final int MAX_LINES = 4000;
    private static int left = 0;
    private static long clientTick = 0;
    private static long frames = 0;
    private static int lines = 0;
    private static int moves = 0;
    private static boolean haveSet = false;
    private static double setX, setMinY, setY, setZ;
    private static boolean havePrev = false;
    private static double prevX, prevY, prevZ, prevVx, prevVy, prevVz;

    private GrugDriftDiagClient() {}

    private static void out(String text) {
        if (lines++ < MAX_LINES) {
            System.out.println("[GRUG CI] DIAG-DRIFT client " + text);
        }
    }

    public static void frame() {
        frames++;
    }

    private static String pose(PlayerEntity p) {
        return p.x
                + ", "
                + p.y
                + ", "
                + p.z
                + " minY "
                + p.shape.minY
                + " vel "
                + p.velocityX
                + ", "
                + p.velocityY
                + ", "
                + p.velocityZ
                + " onGround "
                + p.onGround;
    }

    public static void beforeHandle(Minecraft mc, PlayerMovePacket p) {
        moves++;
        left = WINDOW;
        haveSet = p.hasPos;
        if (p.hasPos) {
            setX = p.x;
            setMinY = p.minY;
            setY = p.y;
            setZ = p.z;
        }
        out(
                "move#"
                        + moves
                        + " packet BEFORE apply at tick "
                        + clientTick
                        + " frame "
                        + frames
                        + " packet x,minY,y,z "
                        + p.x
                        + ", "
                        + p.minY
                        + ", "
                        + p.y
                        + ", "
                        + p.z
                        + " hasPos "
                        + p.hasPos
                        + " player "
                        + (mc.player == null ? "none" : pose(mc.player)));
    }

    public static void afterHandle(Minecraft mc) {
        out(
                "move#"
                        + moves
                        + " packet AFTER apply at tick "
                        + clientTick
                        + " frame "
                        + frames
                        + " player "
                        + (mc.player == null ? "none" : pose(mc.player)));
    }

    /** Called at the head of every client tick. */
    public static void tick(Minecraft mc) {
        clientTick++;
        if (left <= 0 || mc.player == null) {
            return;
        }
        int index = WINDOW - left + 1;
        left--;
        PlayerEntity p = mc.player;
        String solid = "n/a";
        if (mc.world != null) {
            solid = "" + mc.world.getCollisions(p, p.shape).size();
        }
        String prev =
                havePrev
                        ? " since previous tick dpos "
                                + (p.x - prevX)
                                + ", "
                                + (p.y - prevY)
                                + ", "
                                + (p.z - prevZ)
                                + " dvel "
                                + (p.velocityX - prevVx)
                                + ", "
                                + (p.velocityY - prevVy)
                                + ", "
                                + (p.velocityZ - prevVz)
                        : "";
        havePrev = true;
        prevX = p.x;
        prevY = p.y;
        prevZ = p.z;
        prevVx = p.velocityX;
        prevVy = p.velocityY;
        prevVz = p.velocityZ;
        out(
                "move#"
                        + moves
                        + " t+"
                        + index
                        + " tick "
                        + clientTick
                        + " frame "
                        + frames
                        + " pos "
                        + pose(p)
                        + " solidBoxesIntersectingPlayer "
                        + solid
                        + prev
                        + " delta from set "
                        + (haveSet
                                ? (p.x - setX) + ", " + (p.y - setY) + ", " + (p.z - setZ)
                                : "n/a"));
    }

    /** Called after the local player queued its position packet. */
    public static void sent(double x, double minY, double y, double z, boolean moved) {
        if (!haveSet || left <= 0 || !moved) {
            return;
        }
        boolean differs =
                Math.abs(x - setX) > 1e-9
                        || Math.abs(minY - setMinY) > 1e-9
                        || Math.abs(y - setY) > 1e-9
                        || Math.abs(z - setZ) > 1e-9;
        out(
                "move#"
                        + moves
                        + " SENT position packet at tick "
                        + clientTick
                        + " frame "
                        + frames
                        + " x,minY,y,z "
                        + x
                        + ", "
                        + minY
                        + ", "
                        + y
                        + ", "
                        + z
                        + (differs
                                ? " DIFFERS-FROM-SET by "
                                        + (x - setX)
                                        + ", "
                                        + (y - setY)
                                        + ", "
                                        + (z - setZ)
                                : " same as set"));
    }
}
