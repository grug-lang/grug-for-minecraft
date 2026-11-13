package net.grug.minecraft.ornithe.server;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugTestOrigin;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.ornithe.ServerOrnitheAdapter;
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.network.Connection;
import net.minecraft.network.packet.PlayerMovePacket;
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

    /** The player as the snap found them, so the end of the test can put them back (#1161). */
    private static final GrugTestPlayerSnap.ServerSave SNAP = new GrugTestPlayerSnap.ServerSave();

    private static World world = null;
    private static long waitStartMillis = 0;
    private static GrugTestRunner runner = null;
    private static boolean done = false;
    private static boolean advancing = false;
    private static int gameSpeed = 1;

    /**
     * The feet height the run started at, which the hold puts the player back on. It is {@code y}
     * itself, because this server keeps the feet in the player's position (#1190).
     */
    private static double holdY = 0;

    /** How far the feet may drift from that height before the hold corrects them. */
    private static final double HOLD_TOLERANCE = 0.25;

    /**
     * How many times the floor hold put the player back on the ground, and when it last said so.
     */
    private static long floorHolds = 0;

    private static long lastHoldReportMillis = 0;

    /** How many times the hold corrected the player, and when it last said so. */
    private static long holds = 0;

    /**
     * Whether the floor hold has already moved the player in this server tick. The start-height
     * hold is skipped for the rest of the tick when it has, because the floor hold lifting the
     * player in one call and the start-height hold pulling them back in the next would leave them
     * where they started the tick. See #1150 and #1168.
     */
    private static boolean heldOnFloorThisTick = false;

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

    /**
     * Sends the block changes the player manager owes its clients.
     *
     * <p>Alpha sends them from the player manager's tick, which the server runs after the world's
     * own tick, so a test frozen inside that tick never reaches it: a placement it made would sit
     * in the queue while its capture read a client that had not been told (#1123). The player
     * manager's tick is therefore called here too, as b1.7.3-ornithe's {@code flushBlockChanges}
     * does, which sends only the changes queued since the last call.
     */
    public static void flushBlockChanges() {
        GrugPlayerManagerAccess.flushQueuedChanges();
    }

    /** Called at the end of every tick of a server world. */
    public static void onWorldTick(World tickedWorld) {
        // A test's own tick may run the world again through #advance, and the flush belongs after
        // the whole burst, not inside it (#1191).
        if (advancing) {
            return;
        }
        // A menu holds the player's inventory, which holds the player, so the menu's own value
        // keeps its key alive in the weak map; a player who left would leave the entry behind.
        ServerOrnitheAdapter.forgetMenusOfMissingPlayers(tickedWorld.players);
        if (!done && "true".equals(System.getenv("GRUG_CI"))) {
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
        // Every tick of a server world, in a CI run or not: the transport is installed whenever a
        // dedicated server is up and holds a change until it is flushed, so a server outside CI
        // would otherwise hold every change it ever made (#1191). A redraw this flush sends goes
        // after the block changes the tick queued, because the client drops a redraw for a
        // position whose block it has not seen change yet (#1211).
        if (GrugSavedDataSync.hasHeldRedraws()) {
            flushBlockChanges();
        }
        GrugSavedDataSync.flush(tickedWorld);
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
        heldOnFloorThisTick = false;

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            // The run starts when a player has started it (#1040): the joined client sends its
            // start
            // once it is in the world. The player is held on the server's own floor before
            // readiness is asked, so a client that reported a position a fraction inside the ground
            // can land and be accepted (#1136).
            Object testPlayer = GrugCore.getAdapter().testPlayer();
            if (testPlayer instanceof PlayerEntity) {
                holdOnFloor((PlayerEntity) testPlayer);
            }
            if (!players.isEmpty()
                    && GrugControlAlpha.server().hasStarter()
                    && GrugControlAlpha.starterPlayer() != null
                    && GrugCore.getAdapter().isWorldReady(testPlayer)) {
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Firing tests!");
                runner = new GrugTestRunner();
                holdY = ((PlayerEntity) testPlayer).y;
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
                                    + player.y
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
        // Applies what the joined client sent during the previous tick's calls, which here is a
        // forwarded click's packet (#1094). The game's own tick reads the connection too, but only
        // after this runner tick, and a test asserts on the tick after its click.
        GrugControlAlpha.server().applyClientWork();
        runner.tick(testPlayer);
        if (runner.isFinished()) {
            stop(runner.isPassed() ? 0 : 1);
        }
    }

    /**
     * Puts the test player back on the ground the server's own world has under them.
     *
     * <p>A client that fell through the terrain while its chunks were arriving reports a position a
     * fraction of a block inside the ground, and the server accepts it because the box its
     * collision check tests is contracted by 0.0625. The client's own collision then never reports
     * support, so the wait for {@code onGround} never ended (#1136, the rule #1095 added to
     * b1.7.3-ornithe). The ground is in the server's world, so the server teleports the player to
     * the top of the collision column under them whenever they are below it, and zeroes {@code
     * velocityY} so the fall does not continue. The top is the block's collision shape, not its
     * full cube, so a player on a slab is put on the slab's top at 70.5 rather than half a block
     * above it (#1102).
     *
     * <p>It runs before readiness is asked and before each runner tick. It does nothing while a
     * rebuild has cleared the floor and the player is falling, because there is no floor to put
     * them on yet. No host function moves the player down, so holding the feet at the floor cannot
     * hide a test's own movement. The move is sent to the client for the reason {@link #moveTo}
     * gives, and it also stops the start-height hold for the rest of this server tick.
     */
    private static void holdOnFloor(PlayerEntity player) {
        World world = world();
        if (world == null) {
            return;
        }
        GrugPlayerFloor.BlockSource blocks = ServerOrnitheAdapter.solidBlocks(world);
        double feet = player.y;
        double floor = GrugPlayerFloor.floorTop(blocks, player.x, feet, player.z);
        if (!GrugPlayerFloor.below(feet, floor)) {
            return;
        }
        moveTo(player, floor);
        heldOnFloorThisTick = true;
        floorHolds++;
        long now = System.currentTimeMillis();
        if (floorHolds == 1 || now - lastHoldReportMillis >= 5000) {
            lastHoldReportMillis = now;
            System.out.println(
                    "[GRUG CI] holding the test player on the floor at "
                            + floor
                            + ", up from "
                            + feet
                            + " (inside the ground)");
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
     * <p>The floor hold above runs first and answers the case where the client is inside the
     * ground; this one keeps the room's own height. It is skipped for the rest of a server tick
     * once the floor hold has moved the player, so the two cannot undo each other within one tick.
     * No host function moves the player, so the hold cannot hide a test's own movement. The
     * tolerance is there because the world's own tick can leave the feet a fraction of a block off
     * their height, and correcting that on every tick would send a move packet per tick.
     */
    private static void holdPlayer(PlayerEntity player) {
        holdOnFloor(player);
        if (heldOnFloorThisTick) {
            return;
        }
        double feet = player.y;
        if (Math.abs(feet - holdY) <= HOLD_TOLERANCE) {
            return;
        }
        moveTo(player, holdY);
        holds++;
        long now = System.currentTimeMillis();
        if (holds == 1 || now - lastHoldReportMillis >= 5000) {
            lastHoldReportMillis = now;
            System.out.println(
                    "[GRUG CI] holding the test player at " + holdY + ", back from " + feet);
        }
    }

    /**
     * Moves the server's copy of the player's feet to {@code feetY} and tells the client.
     *
     * <p>A server-side move writes the server's copy and tells no one, and the camera is the
     * client's: without a packet the room would be built where the server's player now is while the
     * frame is drawn from where the client's still is, which walks the fixture out of the band the
     * saved-data tests capture.
     *
     * <p>The two sides name the height differently. This server keeps the feet in the player's
     * position (its movement handler stores the client's {@code minY}, which the client fills with
     * its feet), so {@code updatePositionAndAngles} is given the feet. The joined client's position
     * is its eye, so the packet carries the eye in {@code minY}, which is the field its handler
     * writes into the client's position, and the feet in {@code y}. That is exactly what this
     * server's own {@code ServerPlayNetworkHandler.teleport} sends (#1190).
     *
     * <p>The packet goes through the client's player-move handler rather than the entity teleport
     * packet, because that is the handler the game itself uses for a server correction: it sets the
     * client's {@code lastY} along with its position, zeroes the velocity and has the client report
     * the position back. The view transform interpolates the camera from {@code lastY}, so a frame
     * drawn after the entity teleport packet (which sets only the position) is drawn from a height
     * between the old and the new one, while one drawn after this handler is drawn from the height
     * the room was built around.
     */
    private static void moveTo(PlayerEntity player, double feetY) {
        moveTo(player, feetY, player.x, player.z);
    }

    private static void moveTo(PlayerEntity player, double feetY, double x, double z) {
        moveTo(player, feetY, x, z, player.yaw, player.pitch);
    }

    private static void moveTo(
            PlayerEntity player, double feetY, double x, double z, float yaw, float pitch) {
        player.updatePositionAndAngles(x, feetY, z, yaw, pitch);
        player.velocityY = 0.0;
        Connection connection = GrugControlAlpha.connectionFor(player);
        if (connection != null) {
            connection.send(
                    new PlayerMovePacket.PositionAndAngles(
                            x, feetY + GrugTestOrigin.EYE_HEIGHT, feetY, z, yaw, pitch, false));
        }
    }

    /**
     * Snaps the server's copy of the test player to the centre of the block they stand in on x and
     * z, with yaw and pitch at 0, before the runner builds the room (#1161).
     *
     * <p>A client-only run's player is snapped by the adapter, so its room, origin and fixtures sit
     * on the block grid with a level view; a dedicated server skipped that and built from wherever
     * the joined client stood. The height is the feet, which this server keeps in {@code y}, and is
     * left to the holds above; the player is not moved again after the build. {@link #moveTo} sends
     * the packet that carries the joined client's camera and view with the player. The player as
     * they were is saved first, so {@link #restoreTestPlayer} can put them back.
     */
    public static void snapToBlockCentre(PlayerEntity player) {
        if (player == null) {
            return;
        }
        SNAP.save(player, player.x, player.y, player.z, player.yaw, player.pitch);
        moveTo(
                player,
                SNAP.snappedY(),
                SNAP.snappedX(),
                SNAP.snappedZ(),
                SNAP.snappedYaw(),
                SNAP.snappedPitch());
    }

    /**
     * Puts the server's copy of the test player back where {@link #snapToBlockCentre} found them,
     * with the view they had, so a test that moved the player leaves nothing behind for the next
     * one to inherit. The runner calls this when every test ends, whether it passed, failed or
     * aborted.
     *
     * <p>The move sends the packet, so the joined client's camera and view go back with the player.
     * A server whose snap did not run, because it had no player copy yet, restores nothing.
     */
    public static void restoreTestPlayer(PlayerEntity player) {
        if (!SNAP.claimRestore(player)) {
            return;
        }
        moveTo(
                player,
                SNAP.savedY(),
                SNAP.savedX(),
                SNAP.savedZ(),
                SNAP.savedYaw(),
                SNAP.savedPitch());
        SNAP.clear();
    }

    /** Ends the server process; a non-zero status says the run did not pass. */
    private static void stop(int status) {
        done = true;
        GrugControlAlpha.server().endRun();
        if (floorHolds > 0) {
            System.out.println(
                    "[GRUG CI] The test player was held on the floor " + floorHolds + " times.");
        }
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
