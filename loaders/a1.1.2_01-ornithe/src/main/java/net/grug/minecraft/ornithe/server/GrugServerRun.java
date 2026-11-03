package net.grug.minecraft.ornithe.server;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.world.World;

import java.util.List;

/**
 * Drives the test runner on an Alpha dedicated server, from the server world's own tick. The client
 * drives it from its tick in {@code GrugClientHooks}, and a dedicated server has no client, so
 * without this nothing there starts a suite. See #1021 and #167.
 *
 * <p>Alpha a0.2.1 has no server lifecycle events in the OSL jars this layer resolves, and the one
 * world is ticked once per server tick, so a mixin at the end of {@code World.tick} on the server
 * ({@code ServerWorldMixin}) hands the world here. The run starts once a player has joined, and the
 * deadline keeps a client that never connects from hanging it. When the run ends, passed or failed,
 * the process exits, which is what ends the scripts watching it.
 */
@GrugGenerated("a dedicated server, whose coverage no CI run merges until #167 stage 4")
public final class GrugServerRun {
    /** How long to wait for a client to join before failing the run. */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = 240_000;

    private static World world = null;
    private static long waitStartMillis = 0;
    private static GrugTestRunner runner = null;
    private static boolean done = false;
    private static boolean advancing = false;

    private GrugServerRun() {}

    /** The world that last ticked, which is the dedicated server's only one. */
    public static World world() {
        return world;
    }

    /** Called at the end of every tick of a server world. */
    public static void onWorldTick(World tickedWorld) {
        if (done || advancing || !"true".equals(System.getenv("GRUG_CI"))) {
            return;
        }
        try {
            tick(tickedWorld);
        } catch (Throwable thrown) {
            // The server loop would end the process without a word, which reads as a hang to
            // whoever watches the log, so say what stopped the run.
            System.out.println("[GRUG CI] FAIL the server run threw " + thrown);
            thrown.printStackTrace(System.out);
            stop();
        }
    }

    /**
     * Runs the world's own tick {@code ticks} times from inside a test's tick, which is what {@code
     * Test.tick_level} asks for (#780). The tick this runs would hand the world back to {@link
     * #onWorldTick}, so that hook ignores the ticks run here. Answers how many ticks ran.
     */
    public static int advance(World target, int ticks) {
        advancing = true;
        try {
            for (int tick = 0; tick < ticks; tick++) {
                target.tickEntities();
                target.tick();
            }
        } finally {
            advancing = false;
        }
        return ticks;
    }

    private static long lastReportMillis = 0;

    private static void tick(World tickedWorld) {
        world = tickedWorld;
        List<?> players = tickedWorld.players;

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            if (!players.isEmpty() && GrugCore.getAdapter().isWorldReady(players.get(0))) {
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Firing tests!");
                runner = new GrugTestRunner();
            } else if (System.currentTimeMillis() - waitStartMillis >= PLAYER_JOIN_TIMEOUT_MILLIS) {
                System.out.println("[GRUG CI] FAIL world readiness");
                System.out.println(
                        "[GRUG CI] No player joined the dedicated server within "
                                + (PLAYER_JOIN_TIMEOUT_MILLIS / 1000)
                                + " seconds, so the tests were not started.");
                stop();
                return;
            } else {
                long now = System.currentTimeMillis();
                if (!players.isEmpty() && now - lastReportMillis >= 5000) {
                    lastReportMillis = now;
                    net.minecraft.entity.mob.player.PlayerEntity player =
                            (net.minecraft.entity.mob.player.PlayerEntity) players.get(0);
                    System.out.println(
                            "[GRUG CI] waiting for the world: player at "
                                    + player.x
                                    + ", "
                                    + player.shape.minY
                                    + ", "
                                    + player.z);
                }
                return;
            }
        }

        if (players.isEmpty()) {
            System.out.println("[GRUG CI] FAIL the test player left");
            System.out.println(
                    "[GRUG CI] Every player disconnected from the dedicated server during the"
                            + " run.");
            stop();
            return;
        }

        runner.tick(players.get(0));
        if (runner.isFinished()) {
            stop();
        }
    }

    private static void stop() {
        done = true;
        System.out.flush();
        System.exit(0);
    }
}
