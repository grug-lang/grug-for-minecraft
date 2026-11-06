package net.grug.minecraft.stationapi.server;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.stationapi.ServerStationApiAdapter;
import net.grug.minecraft.stationapi.control.GrugControlStation;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.List;

/**
 * Drives the test runner on a b1.7.3 dedicated server, from the end of the server's own tick. The
 * client drives it from its tick in {@code GrugClientHooks}, and a dedicated server has no client,
 * so without this nothing there starts a suite. See #165 and #167.
 *
 * <p>StationAPI hands the tick over ({@code GameTickEvent.End}) and the join ({@code
 * PlayerLoginEvent}); the event carries no server, and StationAPI has no server singleton, so the
 * server is read off the first player who logs in. The run starts once a player is world-ready, and
 * the deadline keeps a client that never connects from hanging it. When the run ends, passed or
 * failed, the process exits, which is what ends the scripts watching it.
 */
@GrugGenerated("a dedicated server, whose coverage no CI run merges until #167 stage 4")
public final class GrugServerRun {
    /** How long to wait for a client to join before failing the run. */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = 240_000;

    private static MinecraftServer server;
    private static GrugTestRunner runner;
    private static long waitStartMillis;
    private static long lastReportMillis;
    private static boolean done;
    private static boolean advancing;
    // Zero until a test sets a speed, which runs one step. No field has an initializer, which
    // leaves this
    // class without a static initializer, whose coverage would depend on which test first touched
    // it.
    private static int gameSpeed;

    /** How many times the hold put the player back on the floor, and when it last said so. */
    private static long holds;

    private static long lastHoldReportMillis;

    private GrugServerRun() {}

    /** Remembers the server a player logged in to, which is the only handle StationAPI gives. */
    public static void onServer(MinecraftServer joined) {
        server = joined;
    }

    /**
     * Sends {@code packet} to the players in {@code world}, and to no one else: the packet carries
     * a position, and a client in another dimension applies it to whatever block sits at those
     * coordinates in its own world.
     */
    public static void sendToWorld(World world, Packet packet) {
        if (server != null && server.playerManager != null) {
            server.playerManager.sendToDimension(packet, world.dimension.id);
        }
    }

    /** The overworld, or null before anyone has logged in. */
    public static World world() {
        return server == null ? null : server.getWorld(0);
    }

    /**
     * Sends the block changes the chunk maps have queued, which the server only does at the end of
     * its own tick: a frozen tick never reaches it (#1040).
     */
    public static void flushBlockChanges() {
        if (server != null) {
            server.playerManager.updateAllChunks();
        }
    }

    /** The connected players, newest last, as an untyped list (see the server adapter). */
    public static List<?> players() {
        return server == null ? Collections.emptyList() : server.playerManager.players;
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
    public static void onServerTick() {
        if (done || advancing || server == null || !"true".equals(System.getenv("GRUG_CI"))) {
            return;
        }
        try {
            tick();
        } catch (Throwable thrown) {
            // The server loop would end the process without a word, which reads as a hang to
            // whoever watches the log, so say what stopped the run.
            System.out.println("[GRUG CI] FAIL the server run threw " + thrown);
            thrown.printStackTrace(System.out);
            stop(1);
        }
    }

    /** Runs the world's own tick from inside a test's tick, in the order the server runs it. */
    private static void advance(World target, int ticks) {
        advancing = true;
        try {
            for (int tick = 0; tick < ticks; tick++) {
                target.tick();
                while (target.doLightingUpdates()) {}
                target.tickEntities();
            }
        } finally {
            advancing = false;
        }
    }

    /**
     * Looks for changed mod files, which a client does every tick and a server has no hook for.
     * Without it a hot reload a test asks for never happens. The server reads its data pack once at
     * startup, as its own loader has no reload hook, so a changed resource counts as reloaded at
     * once.
     */
    private static void pollModFiles() {
        String[] changed =
                GrugModsWatcher.update(message -> System.out.println("[GRUG CI] " + message));
        if (changed.length > 0) {
            GrugModsWatcher.noteLoaderReloadFinished(GrugModsWatcher.takePendingReloadPaths());
        }
    }

    private static void tick() {
        List<?> players = players();
        pollModFiles();

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            // The player is held on the server's own floor before readiness is asked, so a client
            // that reported a position a fraction inside the ground can land and be accepted
            // (#1136).
            PlayerEntity waiting = (PlayerEntity) GrugCore.getAdapter().testPlayer();
            if (waiting != null) {
                holdPlayer(waiting);
            }
            // The run starts when a player has started it (#1040): the joined client sends its
            // start once it is in the world.
            if (!players.isEmpty()
                    && GrugControlStation.server().hasStarter()
                    && GrugControlStation.starterPlayer() != null
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
                                    + player.boundingBox.minY
                                    + ", "
                                    + player.z);
                }
                return;
            }
        }

        if (players.isEmpty() || !players.contains(GrugControlStation.starterPlayer())) {
            GrugControlStation.server().onDisconnect(GrugControlStation.server().starter());
            System.out.println("[GRUG CI] FAIL the test player left");
            System.out.println(
                    "[GRUG CI] Every player disconnected from the dedicated server during the"
                            + " run.");
            stop(1);
            return;
        }

        // The runner ticks once per world tick, as the client's does at speed: a server tick has
        // run the world once already, and each extra tick of a faster game is a world tick followed
        // by a runner tick (#163).
        for (int step = 0; step < Math.max(1, gameSpeed); step++) {
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
     * support, so the wait for {@code onGround} never ended (#1136). The ground is in the server's
     * world, so the server teleports the player to the top of the collision column under them
     * whenever they are below it, and zeroes {@code velocityY} so the fall does not continue. The
     * top is the block's collision shape, not its full cube, so a player on a slab is put on the
     * slab's top at 70.5 rather than half a block above it (#1102). Once the feet are exactly on
     * the floor the client lands on it and reports support again.
     *
     * <p>The same hold runs before each runner tick, so a player who ends a tick below the room's
     * floor is put back on it before the tests see them. It does nothing while a rebuild has
     * cleared the floor and the player is falling, because there is no floor to put them on yet. No
     * host function moves the player down, so holding the feet at the floor cannot hide a test's
     * own movement.
     */
    private static void holdPlayer(PlayerEntity player) {
        World world = world();
        if (world == null) {
            return;
        }
        GrugPlayerFloor.BlockSource blocks = ServerStationApiAdapter.solidBlocks(world);
        double feet = player.boundingBox.minY;
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
        GrugControlStation.server().endRun();
        if (holds > 0) {
            System.out.println(
                    "[GRUG CI] The test player was held on the floor " + holds + " times.");
        }
        System.out.flush();
        System.exit(status);
    }
}
