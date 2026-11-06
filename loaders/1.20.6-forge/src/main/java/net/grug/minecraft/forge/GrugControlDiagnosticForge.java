package net.grug.minecraft.forge;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.awt.image.BufferedImage;

/** Client-only: the diagnostic of a forwarded capture, kept out of the adapter a server loads. */
public final class GrugControlDiagnosticForge {
    private GrugControlDiagnosticForge() {}

    private static int count;

    /**
     * Under GRUG_CI, saves the frame a forwarded capture returned and logs what this client's level
     * looks like, to tell a wall that never arrived from a camera that looks elsewhere (#1040,
     * #1161).
     */
    @GrugGenerated(
            "a diagnostic of the multiplayer job's forwarded capture, only read from its artifact")
    public static void describe(BufferedImage image, Minecraft mc) {
        if (!"true".equals(System.getenv("GRUG_CI")) && System.getenv("GRUG_CI_CONNECT") == null) {
            return;
        }
        int n = ++count;
        java.io.File dir = new java.io.File(mc.gameDirectory, "control-captures");
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
        Player p = mc.player;
        Level w = mc.level;
        double yawRad = Math.toRadians(p.getYRot());
        double pitchRad = Math.toRadians(p.getXRot());
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        int px = (int) Math.floor(p.getX());
        int py = (int) Math.floor(p.getY());
        int pz = (int) Math.floor(p.getZ());
        java.util.TreeMap<Integer, Integer> ids = new java.util.TreeMap<>();
        for (int x = px - 6; x <= px + 6; x++) {
            for (int y = py - 6; y <= py + 6; y++) {
                for (int z = pz - 6; z <= pz + 6; z++) {
                    int id = blockId(w, x, y, z);
                    if (id != 0) ids.merge(id, 1, Integer::sum);
                }
            }
        }
        String hit = "none within 12";
        for (double t = 0.5; t <= 12; t += 0.25) {
            int id =
                    blockId(
                            w,
                            (int) Math.floor(p.getX() + dx * t),
                            (int) Math.floor(p.getY() + dy * t),
                            (int) Math.floor(p.getZ() + dz * t));
            if (id != 0) {
                hit = "block " + id + " at distance " + t;
                break;
            }
        }
        System.out.println(
                "[GRUG CONTROL] capture "
                        + n
                        + ": player "
                        + p.getX()
                        + " "
                        + p.getY()
                        + " "
                        + p.getZ()
                        + " yaw "
                        + p.getYRot()
                        + " pitch "
                        + p.getXRot()
                        + ", screen "
                        + (mc.screen == null ? "none" : mc.screen.getClass().getName())
                        + ", look ray hits "
                        + hit
                        + ", non-air block ids within 6 of the player (id=count) "
                        + ids);
    }

    /** The registry id of the block at a position, matching the other loaders' numeric ids. */
    private static int blockId(Level w, int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getId(w.getBlockState(new BlockPos(x, y, z)).getBlock());
    }
}
