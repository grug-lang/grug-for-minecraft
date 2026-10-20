package net.grug.minecraft.bootstrap;

/** An FMLLoader that reports a version but no side. */
public class SidelessFmlLoader {
    public static FakeFmlLoader.VersionInfo versionInfo() {
        return new FakeFmlLoader.VersionInfo();
    }
}
