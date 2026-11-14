package net.grug.minecraft.ornithe;

import net.minecraft.client.Minecraft;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Throwaway #1213 diagnostic, never to be merged. */
public final class Diag1213 {
    private static String atHead = "none";
    private static String atReturn = "none";

    private Diag1213() {}

    private static Object player() {
        Minecraft mc = net.ornithemc.osl.lifecycle.api.client.MinecraftInstance.get();
        return net.grug.minecraft.ornithe.client.GrugClientPlayer.of(mc);
    }

    static String dump(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    sb.append(f.getName()).append('=').append(f.get(o)).append(' ');
                } catch (Throwable t) {
                    sb.append(f.getName()).append("=?");
                }
            }
        }
        return sb.toString();
    }

    private static String matrix(int which) {
        FloatBuffer b = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(which, b);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 16; i++) sb.append(b.get(i)).append(i < 15 ? "," : "");
        return sb.append(']').toString();
    }

    private static String viewport() {
        IntBuffer v = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, v);
        return v.get(0) + "," + v.get(1) + "," + v.get(2) + "x" + v.get(3);
    }

    /** Called at the head of a drawn frame. */
    public static void frameHead(float partialTick) {
        try {
            Minecraft mc = net.ornithemc.osl.lifecycle.api.client.MinecraftInstance.get();
            atHead =
                    "partial="
                            + partialTick
                            + " window="
                            + mc.width
                            + "x"
                            + mc.height
                            + " display="
                            + Display.getWidth()
                            + "x"
                            + Display.getHeight()
                            + " viewportAtHead="
                            + viewport()
                            + " player{"
                            + dump(player())
                            + "}";
        } catch (Throwable t) {
            atHead = "failed " + t;
        }
    }

    /** Called at the return of a drawn frame. */
    public static void frameReturn() {
        try {
            atReturn =
                    "viewportAtReturn="
                            + viewport()
                            + " projection="
                            + matrix(GL11.GL_PROJECTION_MATRIX);
        } catch (Throwable t) {
            atReturn = "failed " + t;
        }
    }

    /** Called when a capture reads pixels, on a joined client and in a client-only run alike. */
    public static void capture(String path) {
        try {
            System.out.println("[DIAG1213] " + path + " lastFrame: " + atHead);
            System.out.println("[DIAG1213] " + path + " lastFrame: " + atReturn);
            System.out.println(
                    "[DIAG1213] "
                            + path
                            + " nowPlayer{"
                            + dump(player())
                            + "} viewportNow="
                            + viewport());
        } catch (Throwable t) {
            System.out.println("[DIAG1213] " + path + " failed " + t);
        }
    }
}
