package net.grug.minecraft.bootstrap;

/** An FMLLoader whose versionInfo() fails. */
public class ThrowingFmlLoader {
    public static Object versionInfo() {
        throw new IllegalStateException("not launched yet");
    }
}
