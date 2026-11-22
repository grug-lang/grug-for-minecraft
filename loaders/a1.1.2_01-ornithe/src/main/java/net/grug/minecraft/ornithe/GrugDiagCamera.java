package net.grug.minecraft.ornithe;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.mob.player.PlayerEntity;

import org.lwjgl.opengl.Display;

/** Diagnostic only, never to be merged. */
public final class GrugDiagCamera {
    private static String frame = "no frame yet";

    private GrugDiagCamera() {}

    private static String d(double v) {
        return v + "/" + Long.toHexString(Double.doubleToRawLongBits(v));
    }

    private static String f(float v) {
        return v + "/" + Integer.toHexString(Float.floatToRawIntBits(v));
    }

    /** Records the state the frame about to be drawn reads; the capture reads the latest one. */
    public static void record(Minecraft mc, float partial) {
        PlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            frame = "player or world missing";
            return;
        }
        frame =
                "partial="
                        + f(partial)
                        + " x="
                        + d(p.x)
                        + " y="
                        + d(p.y)
                        + " z="
                        + d(p.z)
                        + " lastX="
                        + d(p.lastX)
                        + " lastY="
                        + d(p.lastY)
                        + " lastZ="
                        + d(p.lastZ)
                        + " yaw="
                        + f(p.yaw)
                        + " pitch="
                        + f(p.pitch)
                        + " lastYaw="
                        + f(p.lastYaw)
                        + " lastPitch="
                        + f(p.lastPitch)
                        + " bobbing="
                        + mc.options.viewBobbing
                        + " bob="
                        + f(p.bob)
                        + " lastBob="
                        + f(p.lastBob)
                        + " walk="
                        + f(p.walkDistance)
                        + " lastWalk="
                        + f(p.lastWalkDistance)
                        + " clock="
                        + mc.world.ticks
                        + " window="
                        + mc.width
                        + "x"
                        + mc.height
                        + " display="
                        + Display.getWidth()
                        + "x"
                        + Display.getHeight();
    }

    public static void capture(int waited) {
        System.out.println("DIAG camera at capture (client tick " + waited + ") " + frame);
    }

    /** The pixel hash of the image just read back, so captures can be told apart in the log. */
    public static void hash(java.awt.image.BufferedImage image) {
        long h = 1125899906842597L;
        if (image != null) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    h = 31 * h + image.getRGB(x, y);
                }
            }
        }
        System.out.println("DIAG capture pixel hash " + Long.toHexString(h));
    }
}
