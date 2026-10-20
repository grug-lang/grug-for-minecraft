package net.grug.minecraft.bootstrap;

/** Stands in for Forge's FMLLoader, which reports the game through versionInfo() and getDist(). */
public class FakeFmlLoader {
    public enum Dist {
        CLIENT,
        DEDICATED_SERVER
    }

    public static class VersionInfo {
        public String mcVersion() {
            return minecraft;
        }
    }

    static String minecraft = "1.20.6";
    static Dist dist = Dist.CLIENT;

    public static VersionInfo versionInfo() {
        return new VersionInfo();
    }

    public static Dist getDist() {
        return dist;
    }
}
