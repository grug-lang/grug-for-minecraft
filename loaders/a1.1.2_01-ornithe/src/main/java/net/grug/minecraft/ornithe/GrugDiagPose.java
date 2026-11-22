package net.grug.minecraft.ornithe;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.mob.player.PlayerEntity;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/** Diagnostic only, never to be merged. */
public final class GrugDiagPose {
    private static String pose = "no world frame yet";

    private GrugDiagPose() {}

    private static String hex(FloatBuffer b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            s.append(Integer.toHexString(Float.floatToRawIntBits(b.get(i))));
            s.append(i < 15 ? "," : "");
        }
        return s.toString();
    }

    private static String d(double v) {
        return Long.toHexString(Double.doubleToRawLongBits(v));
    }

    /** At the world overlay hook, inside renderWorld, where the world's matrices are bound. */
    public static void record(Minecraft mc) {
        FloatBuffer mv = BufferUtils.createFloatBuffer(16);
        FloatBuffer pr = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mv);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, pr);
        PlayerEntity p = mc.player;
        pose =
                "mv="
                        + hex(mv)
                        + " proj="
                        + hex(pr)
                        + (p == null
                                ? ""
                                : " eyeHeight="
                                        + Integer.toHexString(Float.floatToRawIntBits(p.eyeHeight))
                                        + " x="
                                        + d(p.x)
                                        + " y="
                                        + d(p.y)
                                        + " z="
                                        + d(p.z)
                                        + " lastY="
                                        + d(p.lastY));
    }

    public static void capture(java.awt.image.BufferedImage image) {
        long h = 1125899906842597L;
        if (image != null) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    h = 31 * h + image.getRGB(x, y);
                }
            }
        }
        System.out.println("DIAG pose hash=" + Long.toHexString(h) + " " + pose);
    }
}
