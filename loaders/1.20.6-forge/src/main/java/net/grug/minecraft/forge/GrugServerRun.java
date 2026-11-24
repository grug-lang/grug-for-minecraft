package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugLinkArms;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugRunPlayers;
import net.grug.minecraft.core.GrugServerJoin;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugTestState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Drives the test runner on a dedicated server, from the server's own tick. The client drives it
 * from its tick in {@code GrugModLoader.ClientForgeEvents}, and a dedicated server has no client,
 * so without this nothing there starts a suite.
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
public final class GrugServerRun {
    /**
     * How long to wait for a client to join before failing the run. A client takes its time to
     * start and connect, so this is wide; it only has to be shorter than the run's own limit.
     */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = GrugServerJoin.timeoutMillis();

    /**
     * How far from the starter a second player is put before the tests read the room (#1027). It
     * only has to be more than the capture-eye tolerance, a block in 1024, and more than the room's
     * radius so the player is outside it; eight blocks is all three.
     */
    static final int STRANGER_DISTANCE = 8;

    /**
     * How far in front of the starter a second player stands while the player model test runs
     * (#1298): near enough to fill a crop of the frame, and far enough that the model, its name tag
     * and the starter's own eye do not overlap.
     */
    static final double IN_VIEW_DISTANCE = 3.0;

    /** The one test that wants a second player in the starter's view, by its file name. */
    static final String PLAYER_MODEL_TEST = "forwarded_player_model-Test.grug";

    /** Whether the second player is standing in the starter's view right now. */
    private boolean strangerInView = false;

    /**
     * The block centre a client-only run's player is snapped to: the test save's own player stands
     * at 0.89, 66, 52.1, so the singleplayer snap lands on the centre of block 0, 52. A joined
     * client drifts from where it joined between tests, and the renderer's single precision
     * arithmetic draws the same room differently at another absolute position, so the snap goes to
     * this block whatever block the joined player was found in (#1550).
     */
    private static final double START_X = 0.5;

    private static final double START_Z = 52.5;

    /** The player as the snap found them, so the end of the test can put them back (#1161). */
    private static final GrugTestPlayerSnap.ServerSave SNAP =
            new GrugTestPlayerSnap.ServerSave(START_X, START_Z);

    private long waitStartMillis = 0;
    private GrugTestRunner runner = null;
    private boolean done = false;

    /** How many times the hold put the player back on the floor, and when it last said so. */
    private final GrugPlayerFloor.HoldLog holdLog = new GrugPlayerFloor.HoldLog();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        GrugLinkArms.runUnless(done, () -> tick(event.getServer()));
    }

    private void tick(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        keepOwnedPlayers(players);

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
            if (GrugServerJoin.mayFire(
                    players,
                    GrugControlForge.server().hasStarter(),
                    testPlayer,
                    () -> GrugCore.getAdapter().isWorldReady(testPlayer))) {
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Firing tests! Test origin is "
                                + GrugCore.getAdapter().getTestOrigin()
                                + ".");
                runner = new GrugTestRunner();
                moveStrangersAway(server);
            } else {
                failJoinTimeoutIfDue(server);
                return;
            }
        }

        // The run belongs to the player who started it, so the question is whether that player is
        // still here, not whether anyone is (#1027). A second player who stays connected must not
        // hide the starter leaving, and must not become the test player the room is built for.
        // Asking for the starter also covers every player leaving, since a cleared starter is not
        // connected, so the older players.isEmpty() question has no case of its own.
        GrugLinkArms.chooseWith(
                GrugLinkArms.missing(starterPlayer(server)),
                server,
                this::failStarterLeft,
                this::runTests);
    }

    /** The runner's tick, once the world is ready and the starter is still here. */
    private void runTests(MinecraftServer server) {
        ServerPlayer testPlayer = starterPlayer(server);
        holdPlayer(testPlayer);
        // Applies what the joined client sent during the previous tick's calls, which here is a
        // forwarded click's packet (#1094). The server's own tick would run it too, but only after
        // this test's tick, and a test asserts on the tick after its click.
        GrugControlForge.server().applyClientWork();
        runner.tick(testPlayer);
        followTheTestsView(server, testPlayer);
        GrugLinkArms.runOn(runner.isFinished(), server, this::stop);
    }

    /**
     * The player who started the run, or null when they have left.
     *
     * <p>1.20.6's control channel names its starter by UUID, so the connected players are asked
     * about that UUID rather than about the list being non-empty (#1027).
     */
    private static ServerPlayer starterPlayer(MinecraftServer server) {
        return server.getPlayerList().getPlayer((UUID) GrugControlForge.server().starter());
    }

    /**
     * Keeps the players this leg owns out of the world's damage (#1738).
     *
     * <p>The run moves its players where the tests need them and reads where they stand, and no
     * test is about the world hurting them. This leg joins its players in the world's default game
     * mode, survival, in a save that already holds hostile mobs, and a zombie slew the second
     * player about a minute into a run. A dead player's body lies down in the game's {@code DYING}
     * pose, below the crop the player model test captures, so that crop stayed the empty room and
     * the test waited out its bound. This is the one leg the run gives a second player to, so it is
     * the one whose players it marks. Which players are due is core's tested {@link
     * GrugRunPlayers#playersStillToKeep}; this only says whether the run owns them, which is the
     * marker that also puts the second player in view, and marks the ones still to mark.
     */
    private static void keepOwnedPlayers(List<ServerPlayer> players) {
        for (ServerPlayer player :
                GrugRunPlayers.playersStillToKeep(
                        players,
                        ServerPlayer::isInvulnerable,
                        GrugLinkArms.known(System.getenv("GRUG_CI_SECOND_PLAYER")))) {
            player.setInvulnerable(true);
            System.out.println(
                    "[GRUG CI] Kept "
                            + player.getGameProfile().getName()
                            + " out of the world's damage: the run owns this leg's players, so the"
                            + " world cannot take one out of a test.");
        }
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
        GrugLinkArms.runOn(
                GrugLinkArms.known(System.getenv("GRUG_CI_SECOND_PLAYER")),
                server,
                GrugServerRun::moveStrangers);
    }

    private static void moveStrangers(MinecraftServer server) {
        // Every caller has just found the starter's player in the same tick, so it is here.
        ServerPlayer starterPlayer = Objects.requireNonNull(starterPlayer(server));
        UUID starter = starterPlayer.getUUID();
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
     * Puts the second player in the starter's view for as long as the player model test is running,
     * and back where {@link #moveStrangersAway} keeps them when it is not (#1298).
     *
     * <p>The test captures the starter's frame with the other player's model in it, which needs
     * that player in front of the starter, and no grug call can move a player. Every other test
     * wants the room to itself, because the goldens are captures of it, so the player is moved in
     * after the test's first tick has taken its empty baseline and out again on the tick that
     * finishes it. The decision is read from the runner's own state: the test is the current one,
     * and it asked to run another tick.
     */
    private void followTheTestsView(MinecraftServer server, ServerPlayer starter) {
        GrugLinkArms.runIfWith(
                GrugLinkArms.known(System.getenv("GRUG_CI_SECOND_PLAYER")),
                this,
                server,
                starter,
                GrugServerRun::followView);
    }

    private void followView(MinecraftServer server, ServerPlayer starter) {
        boolean wanted =
                GrugRunPlayers.modelTestWanted(
                        GrugTestState.currentTestPath,
                        PLAYER_MODEL_TEST,
                        GrugTestState.testNotDone,
                        runner.isFinished());
        if (wanted == strangerInView) {
            return;
        }
        strangerInView = wanted;
        if (!wanted) {
            moveStrangersAway(server);
            return;
        }
        double yaw = Math.toRadians(starter.getYRot());
        double x = starter.getX() - Math.sin(yaw) * IN_VIEW_DISTANCE;
        double z = starter.getZ() + Math.cos(yaw) * IN_VIEW_DISTANCE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == starter) {
                continue;
            }
            player.teleportTo(
                    player.serverLevel(), x, starter.getY(), z, starter.getYRot() + 180.0f, 0.0f);
            System.out.println(
                    "[GRUG CI] Put a second player "
                            + IN_VIEW_DISTANCE
                            + " blocks in front of the starter for the player model capture.");
        }
    }

    /**
     * Snaps the server's copy of the test player to the centre of the block they stand in on x and
     * z, with yaw and pitch at 0, before the runner builds the room (#1161).
     *
     * <p>A client-only run's player is snapped by the adapter, so its room, origin and fixtures sit
     * on the block grid with a level view; a dedicated server skipped that and built from wherever
     * the joined client stood, which put the 1.20.6 room 0.39 blocks off and shrank the fixture in
     * the capture. The height is left to {@link #holdPlayer}, which owns the floor, and the player
     * is not moved again after the build. The teleport is this version's server move, so it sends
     * the packet that carries the joined client's camera and view with the player. The player as
     * they were is saved first, so {@link #restoreTestPlayer} can put them back.
     */
    public static void snapToBlockCentre(ServerPlayer player) {
        GrugLinkArms.deliver(player, GrugServerRun::saveAndSnap);
    }

    private static void saveAndSnap(ServerPlayer player) {
        SNAP.save(
                player,
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot());
        player.connection.teleport(
                SNAP.snappedX(),
                SNAP.snappedY(),
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
     * <p>The teleport sends the packet, so the joined client's camera and view go back with the
     * player rather than staying where the test left them. A server whose snap did not run, because
     * it had no player copy yet, restores nothing.
     */
    public static void restoreTestPlayer(ServerPlayer player) {
        GrugLinkArms.runOn(SNAP.claimRestore(player), player, GrugServerRun::putBack);
    }

    private static void putBack(ServerPlayer player) {
        player.connection.teleport(
                SNAP.savedX(), SNAP.savedY(), SNAP.savedZ(), SNAP.savedYaw(), SNAP.savedPitch());
        SNAP.clear();
    }

    /**
     * Moves the server's copy of the test player to the feet position, keeping their view, through
     * the move the floor hold uses so the joined client's camera follows (#1040).
     */
    public static void moveToFeet(ServerPlayer player, double x, double feetY, double z) {
        player.connection.teleport(x, feetY, z, player.getYRot(), player.getXRot());
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
        GrugPlayerFloor.BlockSource blocks = ServerForgeAdapter.solidBlocks(player.level());
        double feet = player.getY();
        double floor = GrugPlayerFloor.floorTop(blocks, player.getX(), feet, player.getZ());
        GrugLinkArms.runIfWith(
                GrugPlayerFloor.below(feet, floor), player, feet, floor, this::holdOnFloor);
    }

    /**
     * The arm of the floor hold that puts the player back. The decision to enter it is core's
     * {@link GrugPlayerFloor#below}, and the count, the throttle and the text of its report are
     * core's {@link GrugPlayerFloor.HoldLog}, which core's unit tests cover.
     */
    @GrugGenerated(
            "it runs only when the joined client has fallen into the ground, which the client's own"
                + " collision prevents and a test cannot make it do; the teleport and the call of"
                + " core's tested log are all that is in it (#1136)")
    private void holdOnFloor(ServerPlayer player, double feet, double floor) {
        player.teleportTo(player.getX(), floor, player.getZ());
        player.setDeltaMovement(0.0, 0.0, 0.0);
        holdLog.record(System.out, floor, feet, System.currentTimeMillis());
    }

    /**
     * Fails the run when no player joined in time, which only a failing run reaches. Every waiting
     * tick calls it and core's {@link GrugServerJoin#timeoutReportIfDue} answers whether it is due.
     */
    @GrugGenerated(
            "its failing arm ends the server process through stop, with the run failed: a test that"
                    + " reached it would end the very run it is part of; the decision and the text"
                    + " are core's tested GrugServerJoin.timeoutReportIfDue")
    private void failJoinTimeoutIfDue(MinecraftServer server) {
        String[] report =
                GrugServerJoin.timeoutReportIfDue(
                        waitStartMillis, System.currentTimeMillis(), PLAYER_JOIN_TIMEOUT_MILLIS);
        if (report == null) {
            return;
        }
        GrugServerJoin.print(System.out, report);
        stop(server);
    }

    /** Fails the run because the starter left it, which only a failing run reaches. */
    @GrugGenerated(
            "it ends the server process through stop, with the run failed: a test that reached it"
                    + " would end the very run it is part of; the text is core's tested"
                    + " GrugServerJoin.starterLeftReport")
    private void failStarterLeft(MinecraftServer server) {
        int playersLeft = server.getPlayerList().getPlayers().size();
        GrugServerJoin.print(System.out, GrugServerJoin.starterLeftReport(playersLeft));
        stop(server);
    }

    @GrugGenerated(
            "it ends the server process after the last coverage segment is written, so nothing in"
                    + " it can be counted")
    private void stop(MinecraftServer server) {
        done = true;
        GrugControlForge.server().endRun();
        holdLog.printSummary(System.out);
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
