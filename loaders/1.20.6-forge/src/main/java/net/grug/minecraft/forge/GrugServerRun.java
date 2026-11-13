package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugRunPlayers;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * The player who started the run is the test player ({@link ServerForgeAdapter#testPlayer}), and a
 * second player never stands in for it (#1027). When the run ends, passed or failed, the server
 * stops itself, which is what ends the process {@code run-loader.sh} is watching.
 */
@GrugGenerated("a dedicated server, whose coverage no CI run merges until #167 stage 4")
public final class GrugServerRun {
    /**
     * How long to wait for a client to join before failing the run. A client takes its time to
     * start and connect, so this is wide; it only has to be shorter than the run's own limit.
     */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = 240_000;

    /**
     * How far from the starter a second player is put before the tests read the room (#1027). It
     * only has to be more than the capture-eye tolerance, a block in 1024, and more than the room's
     * radius so the player is outside it; eight blocks is all three.
     */
    static final int STRANGER_DISTANCE = 8;

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
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Firing tests!");
                runner = new GrugTestRunner();
                moveStrangersAway(server);
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

        // The run belongs to the player who started it, so the question is whether that player is
        // still here, not whether anyone is (#1027). A second player who stays connected must not
        // hide the starter leaving, and must not become the test player the room is built for.
        // Asking for the starter also covers every player leaving, since a cleared starter is not
        // in the connected list, so the older players.isEmpty() question has no case of its own.
        ServerPlayer testPlayer = starterPlayer(server);
        if (testPlayer == null) {
            System.out.println("[GRUG CI] FAIL the test player left");
            System.out.println(
                    "[GRUG CI] The player who started the run disconnected while "
                            + players.size()
                            + " player(s) were still on the dedicated server.");
            stop(server);
            return;
        }
        holdPlayer(testPlayer);
        // Applies what the joined client sent during the previous tick's calls, which here is a
        // forwarded click's packet (#1094). The server's own tick would run it too, but only after
        // this test's tick, and a test asserts on the tick after its click.
        GrugControlForge.server().applyClientWork();
        runner.tick(testPlayer);
        if (runner.isFinished()) {
            stop(server);
        }
    }

    /**
     * The player who started the run, or null when they have left.
     *
     * <p>1.20.6's control channel names its starter by UUID, so the connected players are asked
     * about that UUID rather than about the list being non-empty (#1027).
     */
    private static ServerPlayer starterPlayer(MinecraftServer server) {
        Object starter = GrugControlForge.server().starter();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        List<UUID> connected = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            connected.add(player.getUUID());
        }
        if (GrugRunPlayers.starterLeft(connected, starter)) {
            return null;
        }
        return server.getPlayerList().getPlayer((UUID) starter);
    }

    /**
     * Puts every player except the starter outside the room before the tests read it (#1027).
     *
     * <p>A second client joins the same world spawn as the starter, so a run that read the player
     * list's first entry would report an eye inside the capture-eye tolerance of the starter's
     * camera and pass the second_player test. Moving that player {@link #STRANGER_DISTANCE} blocks
     * away leaves the wrong pick's origin outside the tolerance, so that test fails instead. The
     * distance is horizontal and the ground is read from the server's own world, so the player is
     * put on the floor there rather than in the air or inside the ground, and the room's walls hide
     * it from every capture.
     *
     * <p>Only the leg that marks a second player does this, so a dedicated server with players of
     * its own keeps them where they are.
     */
    private static void moveStrangersAway(MinecraftServer server) {
        if (System.getenv("GRUG_CI_SECOND_PLAYER") == null) {
            return;
        }
        Object starter = GrugControlForge.server().starter();
        if (!(starter instanceof UUID)) {
            return;
        }
        ServerPlayer starterPlayer = server.getPlayerList().getPlayer((UUID) starter);
        if (starterPlayer == null) {
            return;
        }
        int x = starterPlayer.getBlockX() + STRANGER_DISTANCE;
        int z = starterPlayer.getBlockZ();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(starter)) {
                continue;
            }
            ServerLevel level = player.serverLevel();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            player.teleportTo(level, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
            System.out.println(
                    "[GRUG CI] Moved a second player "
                            + STRANGER_DISTANCE
                            + " blocks from the starter, so a run that picked it would read another"
                            + " player's eye.");
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
        // The run's players are read after the last forwarded call, so a second player who is
        // still here proves the freeze did not drop its connection (#1027). run-multiplayer.sh
        // asserts this line on the 1.20.6 leg, which is the one that launches a second client.
        System.out.println(
                "[GRUG CI] The run ended with "
                        + server.getPlayerList().getPlayers().size()
                        + " player(s) connected.");
        server.halt(false);
    }
}
