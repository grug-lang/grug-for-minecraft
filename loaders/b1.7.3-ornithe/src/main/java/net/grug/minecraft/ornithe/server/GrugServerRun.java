package net.grug.minecraft.ornithe.server;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.ornithe.ServerOrnitheAdapter;
import net.grug.minecraft.ornithe.control.GrugControlBeta;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.mob.player.ServerPlayerEntity;
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

    /** How many times the hold put the player back on the floor, and when it last said so. */
    private static long holds = 0;

    private static long lastHoldReportMillis = 0;

    private GrugServerRun() {}

    /**
     * Sends the block changes the chunk maps have queued, which the server only does at the end of
     * its own tick: a frozen tick never reaches it (#1040).
     */
    public static void flushBlockChanges() {
        net.minecraft.server.MinecraftServer server =
                net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance.get();
        if (server != null) {
            server.playerManager.tick();
        }
    }

    /**
     * Sends {@code packet} to the players in {@code world}, and to no one else: the packet carries
     * a position, and a client in another dimension applies it to whatever block sits at those
     * coordinates in its own world.
     */
    public static void sendToWorld(World world, Packet packet) {
        net.minecraft.server.MinecraftServer server =
                net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance.get();
        if (server != null && server.playerManager != null) {
            server.playerManager.sendPacket(packet, world.dimension.id);
        }
    }

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
            PlayerEntity waiting = (PlayerEntity) GrugCore.getAdapter().testPlayer();
            if (waiting != null) {
                holdPlayer(waiting);
            }
            // The run starts when a player has started it (#1040): the joined client sends its
            // start once it is in the world.
            if (!players.isEmpty()
                    && GrugControlBeta.server().hasStarter()
                    && waiting != null
                    && GrugCore.getAdapter().isWorldReady(waiting)) {
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
            PlayerEntity player = (PlayerEntity) GrugCore.getAdapter().testPlayer();
            if (player != null) {
                holdPlayer(player);
            }
            runner.tick(player);
            if (runner.roomBuiltThisTick()) return;
            if (runner.isFinished()) {
                stop(runner.isPassed() ? 0 : 1);
                return;
            }
        }
    }

    /**
     * Puts the player back on the ground the server's own world has under them.
     *
     * <p>A client that fell through the terrain while its chunks were arriving reports a position a
     * fraction of a block inside the ground, and the server accepts it because the box its
     * collision check tests is contracted by 0.0625. The client's own collision then never reports
     * support, so the wait for {@code onGround} never ends (#1095). The ground is in the server's
     * world, so the server teleports the player to the top of the collision column under them
     * whenever they are below it, and zeroes {@code velocityY} so the fall does not continue. The
     * top is the block's collision shape, not its full cube, so a player on a slab is put on the
     * slab's top at 70.5 rather than half a block above it (#1102). Once the feet are exactly on
     * the floor the client lands on it and reports support again.
     *
     * <p>The same hold runs during the run, so a player who ends a tick below the room's floor is
     * put back on it before the runner ticks. It does nothing while a rebuild has cleared the floor
     * and the player is falling, because there is no floor to put them on yet. No host function
     * moves the player down, so holding the feet at the floor cannot hide a test's own movement.
     */
    private static void holdPlayer(PlayerEntity player) {
        World world = world();
        if (world == null) {
            return;
        }
        GrugPlayerFloor.BlockSource blocks = ServerOrnitheAdapter.solidBlocks(world);
        double feet = player.shape.minY;
        double floor = GrugPlayerFloor.floorTop(blocks, player.x, feet, player.z);
        if (!GrugPlayerFloor.below(feet, floor)) {
            return;
        }
        ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        serverPlayer.networkHandler.teleport(
                serverPlayer.x, floor, serverPlayer.z, serverPlayer.yaw, serverPlayer.pitch);
        serverPlayer.velocityY = 0.0;
        holds++;
        long now = System.currentTimeMillis();
        if (holds == 1 || now - lastHoldReportMillis >= 5000) {
            lastHoldReportMillis = now;
            System.out.println(
                    "[GRUG CI] holding the test player on the floor at "
                            + floor
                            + ", up from "
                            + feet
                            + " (inside the ground)");
        }
    }

    /** Ends the server process; a non-zero status says the run did not pass. */
    private static void stop(int status) {
        done = true;
        GrugControlBeta.server().endRun();
        if (holds > 0) {
            System.out.println(
                    "[GRUG CI] The test player was held on the floor " + holds + " times.");
        }
        System.out.flush();
        System.exit(status);
    }
}
