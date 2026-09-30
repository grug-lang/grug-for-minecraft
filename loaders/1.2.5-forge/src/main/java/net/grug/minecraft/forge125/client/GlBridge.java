package net.grug.minecraft.forge125.client;

import net.grug.minecraft.grug.GrugGenerated;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Reflects the handful of LWJGL 2 OpenGL calls the loader needs.
 *
 * <p>The loader is built by {@code build_loader.sh}, whose javac classpath only carries the
 * decompiled Minecraft/Forge classes and grug core, so {@code org.lwjgl} is not resolvable at
 * compile time. Reflection keeps the loader free of a compile-time LWJGL dependency while still
 * driving the same GL entry points at runtime.
 */
@GrugGenerated("LWJGL is not on the loader's compile classpath, so GL is reached reflectively")
public final class GlBridge {
    private static final Method GL_COLOR_4F;
    private static final Method GL_READ_PIXELS;
    private static final Method GL_GET_ERROR;
    private static final int GL_RGBA;
    private static final int GL_UNSIGNED_BYTE;
    private static final int GL_NO_ERROR;

    static {
        Method color4f = null;
        Method readPixels = null;
        Method getError = null;
        int rgba = 0;
        int unsignedByte = 0;
        int noError = 0;
        try {
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            color4f =
                    gl11.getMethod("glColor4f", float.class, float.class, float.class, float.class);
            readPixels =
                    gl11.getMethod(
                            "glReadPixels",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            ByteBuffer.class);
            getError = gl11.getMethod("glGetError");
            rgba = gl11.getField("GL_RGBA").getInt(null);
            unsignedByte = gl11.getField("GL_UNSIGNED_BYTE").getInt(null);
            noError = gl11.getField("GL_NO_ERROR").getInt(null);
        } catch (Throwable ignored) {
            // Leaving the methods null makes every call below a no-op; the game itself cannot be
            // running without LWJGL, so this path is only reachable in a broken environment.
        }
        GL_COLOR_4F = color4f;
        GL_READ_PIXELS = readPixels;
        GL_GET_ERROR = getError;
        GL_RGBA = rgba;
        GL_UNSIGNED_BYTE = unsignedByte;
        GL_NO_ERROR = noError;
    }

    private GlBridge() {}

    public static void color4f(float red, float green, float blue, float alpha) {
        if (GL_COLOR_4F == null) return;
        try {
            GL_COLOR_4F.invoke(null, red, green, blue, alpha);
        } catch (Exception e) {
            throw new RuntimeException("glColor4f failed", e);
        }
    }

    public static void readPixels(int x, int y, int width, int height, ByteBuffer pixels) {
        if (GL_READ_PIXELS == null) {
            throw new IllegalStateException("glReadPixels is unavailable: LWJGL is not present");
        }
        try {
            GL_READ_PIXELS.invoke(null, x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        } catch (Exception e) {
            throw new RuntimeException("glReadPixels failed", e);
        }
    }

    public static int getError() {
        if (GL_GET_ERROR == null) return GL_NO_ERROR;
        try {
            return ((Integer) GL_GET_ERROR.invoke(null)).intValue();
        } catch (Exception e) {
            throw new RuntimeException("glGetError failed", e);
        }
    }

    public static int noError() {
        return GL_NO_ERROR;
    }
}
