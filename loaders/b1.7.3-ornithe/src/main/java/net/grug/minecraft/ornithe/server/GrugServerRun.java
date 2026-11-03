package net.grug.minecraft.ornithe.server;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.ornithe.control.GrugControlBeta;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.util.List;

/**
 * Drives the test runner on a b1.7.3 dedicated server, from the end of the server's own tick. The
 * client drives it from its tick in {@code GrugClientHooks}, and a dedicated server has no client,
 * so without this nothing there starts a suite. See #163 and #167.
 *
 * <p>OSL hands the server's lifecycle over ({@code MinecraftServerEvents.TICK_END}), so there is no
 * mixin of grug's own. The run starts once a player has joined and landed, and the deadline keeps a
 * client that never connects from hanging it. When the run ends, passed or failed, the process
 * exits, which is what ends the scripts watching it.
 */
@GrugGenerated("a dedicated server, whose coverage no CI run merges until #167 stage 4")
public final class GrugServerRun {
    /** How long to wait for a client to join before failing the run. */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = 240_000;

    private static GrugTestRunner runner = null;
    private static long waitStartMillis = 0;
    private static long lastReportMillis = 0;
    private static boolean done = false;
    private static boolean advancing = false;
    private static int gameSpeed = 1;

    private GrugServerRun() {}

    /** The dedicated server's only world, or null before the server exists. */
    public static World world() {
        net.minecraft.server.MinecraftServer server =
                net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance.get();
        return server == null ? null : server.getWorld(0);
    }

    /**
     * The ticks the world takes for each of the runner's, which is how the server runs a test at
     * the loader's maximum game speed (#677): the server's own loop gives one, and this runs the
     * rest before the runner's tick.
     */
    public static void setGameSpeed(int speed) {
        gameSpeed = speed;
    }

    /** Called at the end of every tick of the server. */
    public static void onServerTick(MinecraftServer server) {
        if (done || advancing || !"true".equals(System.getenv("GRUG_CI"))) {
            return;
        }
        try {
            tick(server);
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
     * Test.tick_level} asks for (#780), in the order the server's own tick runs a world. Answers
     * how many ticks ran.
     */
    public static int advance(World target, int ticks) {
        advancing = true;
        try {
            for (int tick = 0; tick < ticks; tick++) {
                target.tick();
                while (target.doLightUpdates()) {}
                target.tickEntities();
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

    private static void tick(MinecraftServer server) {
        // Deliberately an untyped list: see ServerOrnitheAdapter.testPlayer.
        List<?> players = server.playerManager.players;
        pollModFiles();

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            // The run starts when a player has started it (#1040): the joined client sends its
            // start once it is in the world.
            if (!players.isEmpty()
                    && GrugControlBeta.server().hasStarter()
                    && GrugControlBeta.starterPlayer() != null
                    && GrugCore.getAdapter().isWorldReady(GrugCore.getAdapter().testPlayer())) {
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
                stop(1);
                return;
            } else {
                long now = System.currentTimeMillis();
                if (!players.isEmpty() && now - lastReportMillis >= 5000) {
                    lastReportMillis = now;
                    PlayerEntity player = (PlayerEntity) players.get(0);
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

        if (players.isEmpty() || !players.contains(GrugControlBeta.starterPlayer())) {
            GrugControlBeta.server().onDisconnect(GrugControlBeta.server().starter());
            System.out.println("[GRUG CI] FAIL the test player left");
            System.out.println(
                    "[GRUG CI] Every player disconnected from the dedicated server during the"
                            + " run.");
            stop(1);
            return;
        }

        // The runner ticks once per world tick, as the client's does at speed: a server tick has
        // run the world once already, and each extra tick of a faster game is a world tick followed
        // by a runner tick. Running the extra world ticks first and the runner once let an item a
        // test dropped fall out of the area its check looks in between two runner ticks (#163).
        for (int step = 0; step < gameSpeed; step++) {
            if (step > 0) {
                advance(server.getWorld(0), 1);
            }
            runner.tick(GrugCore.getAdapter().testPlayer());
            if (runner.isFinished()) {
                stop(runner.isPassed() ? 0 : 1);
                return;
            }
        }
    }

    /** Ends the server process; a non-zero status says the run did not pass. */
    private static void stop(int status) {
        done = true;
        GrugControlBeta.server().endRun();
        System.out.flush();
        System.exit(status);
    }
}
