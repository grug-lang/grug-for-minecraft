package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * Drives the test runner on a dedicated server, from the server's own tick. The client drives it
 * from its tick in {@code GrugModLoader.ClientForgeEvents}, and a dedicated server has no client,
 * so without this nothing there starts a suite. See #167.
 *
 * <p>It is registered only on a dedicated server with {@code GRUG_CI} set, and it names no client
 * class, because a dedicated server refuses to load one.
 *
 * <p>The run starts once a player has joined and the world around them can be read, the way the
 * client waits for the chunks the tests build in. The server always has its chunks, so the wait is
 * for the player, and the deadline is what keeps a client that never connects from hanging the run.
 * The first player in the list is the test player ({@link ServerForgeAdapter#testPlayer}). When the
 * run ends, passed or failed, the server stops itself, which is what ends the process {@code
 * run-loader.sh} is watching.
 */
@GrugGenerated("a dedicated server, whose coverage no CI run merges until #167 stage 4")
public final class GrugServerRun {
    /**
     * How long to wait for a client to join before failing the run. A client takes its time to
     * start and connect, so this is wide; it only has to be shorter than the run's own limit.
     */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = 240_000;

    private long waitStartMillis = 0;
    private GrugTestRunner runner = null;
    private boolean done = false;

    /** How many times the hold put the player back on the floor, and when it last said so. */
    private long holds = 0;

    private long lastHoldReportMillis = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        MinecraftServer server = event.getServer();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            // The run starts when a player has started it (#1040): the joined client sends its
            // start
            // once it is in the world, and the screen the forwarded calls use is that player's. The
            // player is held on the server's own floor before readiness is asked, so a client that
            // reported a position a fraction inside the ground can land and be accepted (#1136).
            Object testPlayer = GrugCore.getAdapter().testPlayer();
            if (testPlayer instanceof ServerPlayer) {
                holdPlayer((ServerPlayer) testPlayer);
            }
            if (GrugControlForge.server().hasStarter()
                    && testPlayer != null
                    && GrugCore.getAdapter().isWorldReady(testPlayer)) {
                // The origin is the server-side answer Test.get_origin reports, printed beside the
                // start so a joined client's capture can be compared with it (#1161).
                net.grug.minecraft.grug.Vec3 origin = GrugCore.getAdapter().getTestOrigin();
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Server origin "
                                + (origin == null
                                        ? "none"
                                        : origin.x() + " " + origin.y() + " " + origin.z())
                                + ". Firing tests!");
                runner = new GrugTestRunner();
            } else if (System.currentTimeMillis() - waitStartMillis >= PLAYER_JOIN_TIMEOUT_MILLIS) {
                System.out.println("[GRUG CI] FAIL world readiness");
                System.out.println(
                        "[GRUG CI] No player joined the dedicated server within "
                                + (PLAYER_JOIN_TIMEOUT_MILLIS / 1000)
                                + " seconds, so the tests were not started.");
                stop(server);
                return;
            } else {
                return;
            }
        }

        if (players.isEmpty()) {
            System.out.println("[GRUG CI] FAIL the test player left");
            System.out.println(
                    "[GRUG CI] Every player disconnected from the dedicated server during the"
                            + " run.");
            stop(server);
            return;
        }

        ServerPlayer testPlayer = (ServerPlayer) GrugCore.getAdapter().testPlayer();
        holdPlayer(testPlayer);
        runner.tick(testPlayer);
        if (runner.isFinished()) {
            stop(server);
        }
    }

    /**
     * Puts the player back on the ground the server's own world has under them.
     *
     * <p>A client that fell through the terrain while its chunks were arriving reports a position a
     * fraction of a block inside the ground, and the server accepts it because the box its
     * collision check tests is contracted by 0.0625. The client's own collision then never reports
     * support, so the wait for {@code onGround} never ended (#1136, the rule #1095 added to
     * b1.7.3-ornithe). The ground is in the server's world, so the server teleports the player to
     * the top of the collision column under them whenever they are below it, and zeroes {@code
     * deltaMovement} so the fall does not continue. The top is the block's collision shape, not its
     * full cube, so a player on a slab is put on the slab's top at 70.5 rather than half a block
     * above it (#1102). This version's teleport packet is what tells the joined client, so the
     * camera follows the player the tests are built around.
     *
     * <p>It runs before readiness is asked and before each runner tick. It does nothing while a
     * rebuild has cleared the floor and the player is falling, because there is no floor to put
     * them on yet. No host function moves the player down, so holding the feet at the floor cannot
     * hide a test's own movement.
     */
    private void holdPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        GrugPlayerFloor.BlockSource blocks = ServerForgeAdapter.solidBlocks(player.level());
        double feet = player.getY();
        double floor = GrugPlayerFloor.floorTop(blocks, player.getX(), feet, player.getZ());
        if (!GrugPlayerFloor.below(feet, floor)) {
            return;
        }
        player.teleportTo(player.getX(), floor, player.getZ());
        player.setDeltaMovement(0.0, 0.0, 0.0);
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

    private void stop(MinecraftServer server) {
        done = true;
        GrugControlForge.server().endRun();
        if (holds > 0) {
            System.out.println(
                    "[GRUG CI] The test player was held on the floor " + holds + " times.");
        }
        server.halt(false);
    }
}
