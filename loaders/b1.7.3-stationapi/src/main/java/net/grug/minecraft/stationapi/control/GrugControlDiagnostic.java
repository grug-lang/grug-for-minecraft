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
        try {
            int n = ++count;
            java.io.File dir = new java.io.File("control-captures");
            dir.mkdirs();
            if (image != null) {
                javax.imageio.ImageIO.write(
                        image, "png", new java.io.File(dir, "capture-" + n + ".png"));
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
        } catch (Exception | LinkageError e) {
            System.out.println("[GRUG CONTROL] diagnostic failed: " + e);
        }
    }
}
