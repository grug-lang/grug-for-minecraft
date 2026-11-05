package net.grug.minecraft.ornithe.server;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.minecraft.entity.mob.player.PlayerEntity;
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
    private static int gameSpeed = 1;

    /** The feet height the run started at, which the hold puts the player back on. */
    private static double holdY = 0;

    /** How far the feet may drift from that height before the hold corrects them. */
    private static final double HOLD_TOLERANCE = 0.25;

    /** How many times the hold corrected the player, and when it last said so. */
    private static long holds = 0;

    private static long lastHoldReportMillis = 0;

    /**
     * The ticks the world takes for each of the runner's, which is how the server runs a test at
     * the loader's maximum game speed (#677): the server's own loop gives one, and this runs the
     * rest before the runner's tick.
     */
    public static void setGameSpeed(int speed) {
        gameSpeed = speed;
    }

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
            stop(1);
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

    /**
     * Looks for changed mod files, which a client does every tick and a server has no hook for.
     * Without it a hot reload a test asks for never happens. A server has no resources to reload,
     * so a changed resource counts as reloaded at once.
     */
    private static void pollModFiles() {
        String[] changed =
                GrugModsWatcher.update(message -> System.out.println("[GRUG CI] " + message));
        if (changed.length > 0) {
            GrugModsWatcher.noteLoaderReloadFinished(GrugModsWatcher.takePendingReloadPaths());
        }
    }

    private static long lastReportMillis = 0;

    private static void tick(World tickedWorld) {
        world = tickedWorld;
        List<?> players = tickedWorld.players;
        pollModFiles();

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            // The run starts when a player has started it (#1040): the joined client sends its
            // start
            // once it is in the world.
            Object testPlayer = GrugCore.getAdapter().testPlayer();
            if (!players.isEmpty()
                    && GrugControlAlpha.server().hasStarter()
                    && GrugControlAlpha.starterPlayer() != null
                    && GrugCore.getAdapter().isWorldReady(testPlayer)) {
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Firing tests!");
                runner = new GrugTestRunner();
                holdY = ((PlayerEntity) testPlayer).shape.minY;
            } else if (System.currentTimeMillis() - waitStartMillis >= PLAYER_JOIN_TIMEOUT_MILLIS) {
                System.out.println("[GRUG CI] FAIL world readiness");
                System.out.println(
                        "[GRUG CI] No player joined the dedicated server within "
                                + (PLAYER_JOIN_TIMEOUT_MILLIS / 1000)
                                + " seconds, so the tests were not started.");
                stop(1);
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

        if (players.isEmpty() || !players.contains(GrugControlAlpha.starterPlayer())) {
            GrugControlAlpha.server().onDisconnect(GrugControlAlpha.server().starter());
            System.out.println("[GRUG CI] FAIL the test player left");
            System.out.println(
                    "[GRUG CI] Every player disconnected from the dedicated server during the"
                            + " run.");
            stop(1);
            return;
        }

        if (gameSpeed > 1) {
            advance(tickedWorld, gameSpeed - 1);
        }
        Object testPlayer = GrugCore.getAdapter().testPlayer();
        if (testPlayer instanceof PlayerEntity) {
            holdPlayer((PlayerEntity) testPlayer);
        }
        runner.tick(testPlayer);
        if (runner.isFinished()) {
            stop(runner.isPassed() ? 0 : 1);
        }
    }

    /**
     * Puts the test player back at the height the run started at when a tick leaves them further
     * from it than {@link #HOLD_TOLERANCE} (#1134).
     *
     * <p>The room is rebuilt around the player's feet, so drift moves its walls, ceiling and clear
     * band with the player. A suite's worth of it puts the build's outermost writes outside Alpha's
     * 128 block world, and this loader reports a {@code place_block} the world refused. The room
     * build runs from the server tick and not from a script, so that report has no call to fail: it
     * reached the VM with an empty call stack, which aborted the process instead of failing a test.
     * 1.2.5 and b1.7.3-ornithe have held their test players since #1095 and #1102; Alpha had not.
     *
     * <p>No host function moves the player, so the hold cannot hide a test's own movement. The
     * tolerance is there because the world's own tick can leave the feet a fraction of a block off
     * their height, and correcting that on every tick would send a teleport packet per tick.
     */
    private static void holdPlayer(PlayerEntity player) {
        double feet = player.shape.minY;
        if (Math.abs(feet - holdY) <= HOLD_TOLERANCE) {
            return;
        }
        player.setPositionAndAngles(player.x, holdY, player.z, player.yaw, player.pitch);
        player.velocityY = 0.0;
        holds++;
        long now = System.currentTimeMillis();
        if (holds == 1 || now - lastHoldReportMillis >= 5000) {
            lastHoldReportMillis = now;
            System.out.println(
                    "[GRUG CI] holding the test player at " + holdY + ", back from " + feet);
        }
    }

    /** Ends the server process; a non-zero status says the run did not pass. */
    private static void stop(int status) {
        done = true;
        GrugControlAlpha.server().endRun();
        if (holds > 0) {
            System.out.println(
                    "[GRUG CI] The test player was held at their start height "
                            + holds
                            + " times.");
        }
        System.out.flush();
        System.exit(status);
    }
}
