package net.grug.minecraft.stationapi.control;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;

/** Client-only: the diagnostic of a forwarded capture, kept out of the adapter a server loads. */
public final class GrugControlDiagnostic {
    private GrugControlDiagnostic() {}

    private static int count;

    /**
     * Under GRUG_CI, saves the frame a forwarded capture returned and logs what this client's world
     * looks like, to tell a wall that never arrived from a camera that looks elsewhere (#1040).
     */
    @GrugGenerated(
            "a diagnostic of the multiplayer job's forwarded capture, only read from its artifact")
    public static void describe(BufferedImage image, Minecraft mc) {
        if (!"true".equals(System.getenv("GRUG_CI")) && System.getenv("GRUG_CI_CONNECT") == null) {
            return;
        }
        int n = ++count;
        java.io.File dir =
                new java.io.File(
                        net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(),
                        "control-captures");
        dir.mkdirs();
        if (image != null) {
            try {
                if (!javax.imageio.ImageIO.write(
                        image, "png", new java.io.File(dir, "capture-" + n + ".png"))) {
                    throw new IllegalStateException("no PNG writer for the forwarded capture");
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        net.minecraft.entity.player.PlayerEntity p = mc.player;
        net.minecraft.world.World w = mc.world;
        double yawRad = Math.toRadians(p.yaw);
        double pitchRad = Math.toRadians(p.pitch);
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        int px = (int) Math.floor(p.x);
        int py = (int) Math.floor(p.y);
        int pz = (int) Math.floor(p.z);
        java.util.TreeMap<Integer, Integer> ids = new java.util.TreeMap<>();
        for (int x = px - 6; x <= px + 6; x++) {
            for (int y = py - 6; y <= py + 6; y++) {
                for (int z = pz - 6; z <= pz + 6; z++) {
                    int id = w.getBlockId(x, y, z);
                    if (id != 0) ids.merge(id, 1, Integer::sum);
                }
            }
        }
        String hit = "none within 12";
        for (double t = 0.5; t <= 12; t += 0.25) {
            int id =
                    w.getBlockId(
                            (int) Math.floor(p.x + dx * t),
                            (int) Math.floor(p.y + dy * t),
                            (int) Math.floor(p.z + dz * t));
            if (id != 0) {
                hit = "block " + id + " at distance " + t;
                break;
            }
        }
        System.out.println(
                "[GRUG CONTROL] capture "
                        + n
                        + ": player "
                        + p.x
                        + " "
                        + p.y
                        + " "
                        + p.z
                        + " yaw "
                        + p.yaw
                        + " pitch "
                        + p.pitch
                        + ", screen "
                        + (mc.currentScreen == null
                                ? "none"
                                : mc.currentScreen.getClass().getName())
                        + ", look ray hits "
                        + hit
                        + ", non-air block ids within 6 of the player (id=count) "
                        + ids);
    }

    /**
     * The inputs of the frame a capture reads, logged by both the joined client and a client-only
     * run so one dispatch shows which of them differs (#1629).
     */
    @GrugGenerated("a diagnostic of the capture's render inputs, only read from its artifact")
    public static void renderInputs(String who, Minecraft mc) {
        if (!"true".equals(System.getenv("GRUG_CI")) && System.getenv("GRUG_CI_CONNECT") == null) {
            return;
        }
        net.minecraft.entity.player.PlayerEntity p = mc.player;
        net.minecraft.world.World w = mc.world;
        if (p == null || w == null) return;
        int bx = (int) Math.floor(p.x);
        int by = (int) Math.floor(p.y);
        int bz = (int) Math.floor(p.z);
        net.minecraft.util.math.Vec3d fog = w.getFogColor(0.5F);
        net.minecraft.util.math.Vec3d sky = w.getSkyColor(p, 0.5F);
        System.out.println(
                "[GRUG RENDER INPUTS] "
                        + who
                        + ": time "
                        + w.getTime()
                        + " ambientDarkness "
                        + w.ambientDarkness
                        + " timeOfDay "
                        + w.getTime(0.5F)
                        + " fog "
                        + fog.x
                        + " "
                        + fog.y
                        + " "
                        + fog.z
                        + " sky "
                        + sky.x
                        + " "
                        + sky.y
                        + " "
                        + sky.z
                        + " eye block brightness "
                        + w.getBrightness(bx, by, bz)
                        + " sky "
                        + w.getBrightness(net.minecraft.world.LightType.SKY, bx, by, bz)
                        + " blocklight "
                        + w.getBrightness(net.minecraft.world.LightType.BLOCK, bx, by, bz)
                        + " pos "
                        + p.x
                        + " "
                        + p.y
                        + " "
                        + p.z
                        + " last "
                        + p.lastTickX
                        + " "
                        + p.lastTickY
                        + " "
                        + p.lastTickZ
                        + " yaw "
                        + p.yaw
                        + "/"
                        + p.prevYaw
                        + " pitch "
                        + p.pitch
                        + "/"
                        + p.prevPitch
                        + " viewDistance "
                        + mc.options.viewDistance
                        + " fancy "
                        + mc.options.fancyGraphics
                        + " advGL "
                        + mc.options.advancedOpengl
                        + " window "
                        + mc.displayWidth
                        + "x"
                        + mc.displayHeight
                        + " particles"
                        + particles(mc));
    }

    /**
     * Logs every live client particle with its class and position, because a lone dark pixel on a
     * smooth floor in a capture is the size of a torch's smoke (#1629).
     */
    @GrugGenerated("a diagnostic of the capture's particles, only read from its artifact")
    private static String particles(Minecraft mc) {
        StringBuilder out = new StringBuilder();
        try {
            for (java.lang.reflect.Field f : mc.particleManager.getClass().getDeclaredFields()) {
                if (f.getType() != java.util.List[].class) continue;
                f.setAccessible(true);
                java.util.List<?>[] lists = (java.util.List<?>[]) f.get(mc.particleManager);
                int layer = 0;
                for (java.util.List<?> list : lists) {
                    out.append(" layer")
                            .append(layer++)
                            .append("=")
                            .append(list.size())
                            .append(":");
                    for (Object o : list) {
                        net.minecraft.entity.Entity e = (net.minecraft.entity.Entity) o;
                        out.append(" ")
                                .append(o.getClass().getSimpleName())
                                .append("@")
                                .append(e.x)
                                .append(",")
                                .append(e.y)
                                .append(",")
                                .append(e.z);
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            out.append(" unreadable ").append(e);
        }
        return out.toString();
    }
}
