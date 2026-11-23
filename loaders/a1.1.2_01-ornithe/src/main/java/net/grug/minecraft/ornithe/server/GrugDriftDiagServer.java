package net.grug.minecraft.ornithe.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.world.World;

import java.util.List;

/**
 * DIAGNOSTIC ONLY (branch diag-a1-drift, never merged): logs the server's test player and the
 * non-player entities near it for 40 world ticks after each commanded move, to find what moves the
 * joined client's player off the commanded pose.
 */
public final class GrugDriftDiagServer {
    private static final int WINDOW = 40;
    private static final int MAX_LINES = 4000;
    private static int left = 0;
    private static long worldTick = 0;
    private static int lines = 0;
    private static double cx, cy, cz;
    private static int moves = 0;

    private GrugDriftDiagServer() {}

    private static void out(String text) {
        if (lines++ < MAX_LINES) {
            System.out.println("[GRUG CI] DIAG-DRIFT server " + text);
        }
    }

    /** Called from every moveTo: the feet pose commanded. */
    public static void commanded(String why, double x, double feetY, double z) {
        cx = x;
        cy = feetY;
        cz = z;
        left = WINDOW;
        moves++;
        out(
                "move#"
                        + moves
                        + " "
                        + why
                        + " commanded feet "
                        + x
                        + ", "
                        + feetY
                        + ", "
                        + z
                        + " at world tick "
                        + worldTick);
    }

    /** Called at the end of every world tick, including the ones advance() runs. */
    public static void tick(World world, PlayerEntity player) {
        worldTick++;
        if (left <= 0 || player == null) {
            return;
        }
        int index = WINDOW - left + 1;
        left--;
        StringBuilder near = new StringBuilder();
        int count = 0;
        List<?> entities = world.entities;
        for (Object o : entities) {
            if (!(o instanceof Entity) || o instanceof PlayerEntity) {
                continue;
            }
            Entity e = (Entity) o;
            double dx = e.x - player.x;
            double dy = e.y - player.y;
            double dz = e.z - player.z;
            if (dx * dx + dy * dy + dz * dz <= 9.0) {
                count++;
                near.append(' ')
                        .append(e.getClass().getSimpleName())
                        .append('@')
                        .append(String.format("%.2f,%.2f,%.2f", e.x, e.y, e.z));
            }
        }
        out(
                "move#"
                        + moves
                        + " t+"
                        + index
                        + " wt "
                        + worldTick
                        + " pos "
                        + player.x
                        + ", "
                        + player.y
                        + ", "
                        + player.z
                        + " delta "
                        + (player.x - cx)
                        + ", "
                        + (player.y - cy)
                        + ", "
                        + (player.z - cz)
                        + " vel "
                        + player.velocityX
                        + ", "
                        + player.velocityY
                        + ", "
                        + player.velocityZ
                        + " onGround "
                        + player.onGround
                        + " nearby "
                        + count
                        + near);
    }
}
