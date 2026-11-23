package net.grug.minecraft.stationapi;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

/**
 * A throwaway diagnostic for the dedicated leg's lit redstone engine: what light the block entities
 * near the test player see on this side, logged only when it changes, with a timestamp, so a log
 * shows in which order the server's and the client's light reached each side.
 */
@GrugGenerated("a throwaway diagnostic read only from a CI log")
public final class GrugLightDiag {
    private GrugLightDiag() {}

    private static String last = "";
    private static int lines;

    /** Logs the light at every block entity near the player when it differs from the last log. */
    public static void sample(String side, World world, PlayerEntity player, String why) {
        if (world == null || player == null || lines > 600) return;
        StringBuilder out = new StringBuilder();
        int px = (int) Math.floor(player.x);
        int py = (int) Math.floor(player.boundingBox.minY);
        int pz = (int) Math.floor(player.z);
        int found = 0;
        for (int x = px - 8; x <= px + 8 && found < 6; x++) {
            for (int y = py - 4; y <= py + 2; y++) {
                for (int z = pz + 4; z <= pz + 11; z++) {
                    if (world.getBlockEntity(x, y, z) == null) continue;
                    found++;
                    out.append(" [")
                            .append(x - px)
                            .append(',')
                            .append(y - py)
                            .append(',')
                            .append(z - pz)
                            .append(" block ")
                            .append(world.getBrightness(LightType.BLOCK, x, y, z))
                            .append(" sky ")
                            .append(world.getBrightness(LightType.SKY, x, y, z))
                            .append(" m1782 ")
                            .append(world.method_1782(x, y, z))
                            .append(']');
                }
            }
        }
        String now = out.toString();
        if (now.equals(last) && !why.startsWith("event")) return;
        if (!why.startsWith("event")) last = now;
        lines++;
        System.out.println(
                "[LIGHTDIAG] "
                        + System.currentTimeMillis() % 1000000
                        + " "
                        + side
                        + " ("
                        + why
                        + ") block entities near the player:"
                        + now);
    }
}
