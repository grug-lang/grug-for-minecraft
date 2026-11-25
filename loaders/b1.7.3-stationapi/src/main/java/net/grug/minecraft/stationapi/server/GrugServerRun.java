package net.grug.minecraft.stationapi.server;

import net.grug.minecraft.core.GrugArms;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugLinkArms;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugRunOrigin;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugSendBuffer;
import net.grug.minecraft.core.GrugServerJoin;
import net.grug.minecraft.core.GrugServerRunArms;
import net.grug.minecraft.core.GrugStarterPlacement;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.stationapi.ServerStationApiAdapter;
import net.grug.minecraft.stationapi.control.GrugControlStation;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.Connection;
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
public final class GrugServerRun {
    /** How long to wait for a client to join before failing the run. */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = GrugServerJoin.timeoutMillis();

    private static MinecraftServer server;
    private static GrugTestRunner runner;

    /**
     * The feet height the run holds the test player at, 0 before the run has started: where they
     * stood when it began, rounded to a millionth to shed the float eye height's last bits. A room
     * built while the feet were a hair under a whole number put its stand block a block too low and
     * the hold then kept the player on it, so every later capture was drawn one block lower than a
     * client-only run's (#1068, #1629).
     */
    private static double heldFeetY = 0;

    /** The held feet height, 0 before the run has started. */
    public static double heldFeetY() {
        return heldFeetY;
    }

    private static long waitStartMillis;
    private static long lastReportMillis;
    private static boolean done;
    private static boolean advancing;
    // Zero until a test sets a speed, which runs one step. No field has an initializer, which
    // leaves this
    // class without a static initializer, whose coverage would depend on which test first touched
    // it.
    private static int gameSpeed;

    /**
     * The pace this harness runs the world at: it stops producing world ticks while the joined
     * client's connection holds more than core's low-water mark (#1802).
     */
    private static final GrugSendBuffer SEND_BUFFER = new GrugSendBuffer();

    /** How many world ticks the current wait for the joined client has taken, 0 when it is not. */
    private static int waitTicks = 0;

    /**
     * Where the test save's own player stands, as feet: the position a client-only run's room is
     * built around. A joined player starts from the world's random spawn, and the renderer's single
     * precision arithmetic and the terrain's light differ at another absolute position, so the
     * server places the joined player here before the run starts (#1213).
     */
    private static final double START_X = -18.5;

    private static final double START_FEET_Y = 70.0;
    private static final double START_Z = -63.5;

    /** The player as the snap found them, so the end of the test can put them back (#1161). */
    private static final GrugTestPlayerSnap.ServerSave SNAP =
            new GrugTestPlayerSnap.ServerSave(START_X, START_Z);

    /** Remembers the starter placed at the start above, so each new starter is placed once. */
    private static final GrugStarterPlacement STARTER_PLACEMENT = new GrugStarterPlacement();

    /** How many times the hold put the player back on the floor, and when it last said so. */
    private static final GrugPlayerFloor.HoldLog HOLD_LOG = new GrugPlayerFloor.HoldLog();

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
        GrugServerRunArms.withPresent(
                server,
                joined -> joined.playerManager,
                manager -> manager.sendToDimension(packet, world.dimension.id));
    }

    /** The overworld, or null before anyone has logged in. */
    public static World world() {
        return GrugLinkArms.mapOrNull(server, joined -> joined.getWorld(0));
    }

    /**
     * Sends the block changes the chunk maps have queued, which the server only does at the end of
     * its own tick: a frozen tick never reaches it (#1040).
     */
    public static void flushBlockChanges() {
        GrugServerRunArms.withPresent(
                server, joined -> joined.playerManager, manager -> manager.updateAllChunks());
    }

    /**
     * Whether the server's operator list holds {@code name}, which is the game's own check: {@code
     * PlayerManager.isOperator} lower-cases the name and looks it up in the set {@code ops.txt}
     * fills (#1331).
     */
    public static boolean isOperator(String name) {
        return server.playerManager.isOperator(name);
    }

    /** Adds {@code name} to the operator list, which the game saves to {@code ops.txt}. */
    public static void addToOperators(String name) {
        server.playerManager.addToOperators(name);
    }

    /** Removes {@code name} from the operator list, which the game saves to {@code ops.txt}. */
    public static void removeFromOperators(String name) {
        server.playerManager.removeFromOperators(name);
    }

    /** The connected players, newest last, as an untyped list (see the server adapter). */
    public static List<?> players() {
        return server == null ? Collections.emptyList() : server.playerManager.players;
    }

    /**
     * Closes the screen handler this server has open for {@code player}, as the game's own close
     * does, so both sides are back on the player's own inventory, window 0 (#1094). A player
     * already on their own inventory has nothing to close.
     */
    public static void closeScreenHandler(PlayerEntity player) {
        GrugLinkArms.applyTo(player, ServerPlayerEntity.class, GrugServerRun::closeIfOpen);
    }

    private static void closeIfOpen(ServerPlayerEntity player) {
        GrugLinkArms.runIf(
                player.currentScreenHandler != player.playerScreenHandler,
                player::closeHandledScreen);
    }

    /**
     * Tells {@code player}'s client what this server's cursor holds (#1098). The cursor is not a
     * slot a screen handler's own update sends, so the game syncs it as window -1, which is what
     * {@code updateCursorStack} does.
     */
    public static void sendCursor(PlayerEntity player) {
        GrugLinkArms.applyTo(
                player, ServerPlayerEntity.class, ServerPlayerEntity::updateCursorStack);
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
        // A test's own tick may run the world again through #advance; the flush belongs after the
        // burst, not inside it (#1191).
        if (GrugServerRunArms.either(advancing, server == null)) {
            return;
        }
        if (waitForClient()) {
            // #1802: this tick runs no world tick, no runner tick and no flush, and the server's
            // own loop returns at once: the connection's write thread is what drains the backlog,
            // and it needs the game thread out of the way, not idle work.
            return;
        }
        GrugLinkArms.runUnless(
                done,
                () ->
                        GrugLinkArms.runIfCi(
                                System::getenv,
                                () ->
                                        GrugServerRunArms.guard(
                                                GrugServerRun::tick, GrugServerRun::failThrown)));
        // Every tick, in a CI run or not: the server installs the deferring transport whenever it
        // starts, so a server outside CI would otherwise hold every change it ever made (#1191).
        // The flush covers every world the server runs, after the tick's own block changes. A
        // redraw this flush sends goes after those block changes, because the client drops a
        // redraw for a position whose block it has not seen change yet (#1211).
        GrugLinkArms.runIf(GrugSavedDataSync.hasHeldRedraws(), GrugServerRun::flushBlockChanges);
        GrugSavedDataSync.flush();
    }

    /** Runs the world's own tick from inside a test's tick, in the order the server runs it. */
    private static void advance(World target, int ticks) {
        advancing = true;
        try {
            for (int tick = 0; tick < ticks; tick++) {
                // #1802: a batch of world ticks queues packets for the joined client faster than it
                // applies them, and the game closes the player once its connection holds 1 MiB, so
                // the batch stops here and the test waits for the client instead of losing it.
                if (waitForClient()) {
                    return;
                }
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
            GrugLinkArms.applyTo(waiting, PlayerEntity.class, GrugServerRun::holdPlayer);
            if (placeStarter(waiting)) {
                return;
            }
            // The run starts when a player has started it (#1040): the joined client sends its
            // start once it is in the world.
            if (GrugLinkArms.startReady(
                    players.isEmpty(),
                    () ->
                            GrugLinkArms.ifHere(
                                    GrugControlStation.server().hasStarter(),
                                    () -> GrugLinkArms.known(GrugControlStation.starterPlayer())),
                    GrugCore.getAdapter().testPlayer(),
                    GrugCore.getAdapter()::isWorldReady)) {
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Firing tests!");
                PlayerEntity starter = (PlayerEntity) GrugCore.getAdapter().testPlayer();
                heldFeetY = GrugServerRunArms.roundedHeight(starter.boundingBox.minY);
                System.out.println("[GRUG CI] the run holds the test player at feet " + heldFeetY);
                runner = new GrugTestRunner();
            } else {
                failJoinTimeoutIfDue();
                reportWaiting(players);
                return;
            }
        }

        GrugLinkArms.choose(
                GrugLinkArms.starterLeft(players, GrugControlStation.starterPlayer()),
                GrugServerRun::failPlayerLeft,
                GrugServerRun::stepRunner);
    }

    /**
     * Puts the joined player at the save's start when this starter has not been placed yet, and
     * says whether it did, so the tick leaves the run for the next one while the client settles.
     */
    private static boolean placeStarter(PlayerEntity waiting) {
        return GrugArms.mapTypeOr(
                waiting, ServerPlayerEntity.class, GrugServerRun::placeIfNew, Boolean.FALSE);
    }

    private static Boolean placeIfNew(ServerPlayerEntity placed) {
        if (!STARTER_PLACEMENT.shouldPlace(GrugControlStation.server().starter())) {
            return Boolean.FALSE;
        }
        placed.networkHandler.teleport(START_X, START_FEET_Y, START_Z, 0.0F, 0.0F);
        placed.velocityY = 0.0;
        System.out.println(
                "[GRUG CI] placed the test player at the save's start "
                        + START_X
                        + ", "
                        + START_FEET_Y
                        + ", "
                        + START_Z);
        return Boolean.TRUE;
    }

    /** Ticks the runner once for each step of the game speed, with the player held first. */
    private static void stepRunner() {
        // The runner ticks once per world tick, as the client's does at speed: a server tick has
        // run the world once already, and each extra tick of a faster game is a world tick followed
        // by a runner tick (#163).
        // The server's setup sets the difficulty from "spawn-monsters", which the harness turns off
        // so that nothing spawns around the test player, and difficulty 0 makes the game remove
        // every monster a test spawns in its first tick. Tests run at the difficulty the client's
        // own options default to, normal, and spawn-monsters still decides the spawning (#1040).
        server.getWorld(0).difficulty = 2;
        // The same setup reads "spawn-animals", also turned off by the harness, and the world's
        // entity update then marks every animal dead on its first update, which killed the pigs
        // the mob cap overlay test spawns. The worlds' natural-spawn flags were set from the
        // properties at load and are not read from here, so raising the server's flag keeps
        // natural spawns off and lets a spawned animal live (#1040).
        server.spawnAnimals = true;
        for (int step = 0; step < Math.max(1, gameSpeed); step++) {
            if (step > 0) {
                advance(server.getWorld(0), 1);
            }
            PlayerEntity player = (PlayerEntity) GrugCore.getAdapter().testPlayer();
            GrugLinkArms.applyTo(player, PlayerEntity.class, GrugServerRun::holdPlayer);
            // Applies what the joined client sent during the previous tick's calls, which here is a
            // forwarded click's packet (#1094). The game's own tick reads the connection too, but
            // only after this whole game-speed loop, and a test asserts on the tick after its
            // click.
            GrugControlStation.server().applyClientWork();
            runner.tick(player);
            GrugRunOrigin.reportOnce(GrugCore.getAdapter().getTestOrigin());
            if (runner.roomBuiltThisTick()) return;
            GrugLinkArms.runIf(runner.isFinished(), GrugServerRun::endRun);
        }
    }

    /**
     * Snaps the server's copy of the test player to the centre of the block they stand in on x and
     * z, with yaw and pitch at 0, before the runner builds the room (#1161).
     *
     * <p>A client-only run's player is snapped by the adapter, so its room, origin and fixtures sit
     * on the block grid with a level view; a dedicated server skipped that and built from wherever
     * the joined client stood. The feet are the bottom of the box, as {@link #holdPlayer} reads
     * them, and the height is left to that hold; the player is not moved again after the build. The
     * teleport is the same server move the floor hold uses, so it carries the joined client's
     * camera and view with the player. The player as they were is saved first, so {@link
     * #restoreTestPlayer} can put them back.
     */
    public static void snapToBlockCentre(PlayerEntity player) {
        GrugLinkArms.applyTo(player, ServerPlayerEntity.class, GrugServerRun::snapServerPlayer);
    }

    private static void snapServerPlayer(ServerPlayerEntity serverPlayer) {
        SNAP.save(
                serverPlayer,
                serverPlayer.x,
                serverPlayer.boundingBox.minY,
                serverPlayer.z,
                serverPlayer.yaw,
                serverPlayer.pitch);
        serverPlayer.networkHandler.teleport(
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
     * player. A player this server does not own, or a snap that did not run, restores nothing.
     */
    public static void restoreTestPlayer(PlayerEntity player) {
        GrugLinkArms.runIf(
                SNAP.claimRestore(player),
                () ->
                        GrugLinkArms.applyTo(
                                player, ServerPlayerEntity.class, GrugServerRun::restoreSnap));
    }

    private static void restoreSnap(ServerPlayerEntity serverPlayer) {
        serverPlayer.networkHandler.teleport(
                SNAP.savedX(), SNAP.savedY(), SNAP.savedZ(), SNAP.savedYaw(), SNAP.savedPitch());
        SNAP.clear();
    }

    /**
     * Moves the server's copy of the test player to the feet position, keeping their view, through
     * the move the floor hold uses so the joined client's camera follows (#1040).
     */
    public static void moveToFeet(PlayerEntity player, double x, double feetY, double z) {
        GrugLinkArms.applyTo(
                player,
                ServerPlayerEntity.class,
                serverPlayer -> {
                    serverPlayer.networkHandler.teleport(
                            x, feetY, z, serverPlayer.yaw, serverPlayer.pitch);
                    serverPlayer.velocityY = 0.0;
                    heldFeetY = GrugServerRunArms.movedHeld(heldFeetY, feetY);
                });
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
        GrugLinkArms.applyTo(world(), World.class, world -> holdIn(world, player));
    }

    private static void holdIn(World world, PlayerEntity player) {
        double feet = player.boundingBox.minY;
        GrugServerRunArms.hold(
                player,
                heldFeetY,
                feet,
                HELD_TOLERANCE,
                floorUnder(world, player),
                GrugServerRun::holdAtHeight,
                GrugServerRun::holdOnFloor);
    }

    private static double floorUnder(World world, PlayerEntity player) {
        return GrugPlayerFloor.floorTop(
                ServerStationApiAdapter.solidBlocks(world),
                player.x,
                player.boundingBox.minY,
                player.z);
    }

    private static final double HELD_TOLERANCE = 0.25;

    @GrugGenerated(
            "it runs only when a build or a fall left the player a quarter block under the height"
                    + " the run holds them at, which a test cannot make happen (#1629)")
    private static void holdAtHeight(PlayerEntity player) {
        ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        double feetY = heldFeetY;
        serverPlayer.networkHandler.teleport(
                serverPlayer.x, feetY, serverPlayer.z, serverPlayer.yaw, serverPlayer.pitch);
        serverPlayer.velocityY = 0.0;
        System.out.println("[GRUG CI] holding the test player at the run's height " + feetY);
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
    private static void holdOnFloor(PlayerEntity player, double floor) {
        ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        double feet = player.boundingBox.minY;
        serverPlayer.networkHandler.teleport(
                serverPlayer.x, floor, serverPlayer.z, serverPlayer.yaw, serverPlayer.pitch);
        serverPlayer.velocityY = 0.0;
        HOLD_LOG.record(System.out, floor, feet, System.currentTimeMillis());
    }

    /**
     * Fails the run when no player joined in time, which only a failing run reaches. Every waiting
     * tick calls it and core's {@link GrugServerJoin#timeoutReportIfDue} answers whether it is due.
     */
    @GrugGenerated(
            "its failing arm ends the server process through stop, with the run failed: a test that"
                    + " reached it would end the very run it is part of; the decision and the text"
                    + " are core's tested GrugServerJoin.timeoutReportIfDue")
    private static void failJoinTimeoutIfDue() {
        String[] report =
                GrugServerJoin.timeoutReportIfDue(
                        waitStartMillis, System.currentTimeMillis(), PLAYER_JOIN_TIMEOUT_MILLIS);
        if (report == null) {
            return;
        }
        GrugServerJoin.print(System.out, report);
        stop(1);
    }

    /**
     * Says now and then that the run still waits for the world around a joined player. Core's
     * {@link GrugServerJoin#waitReportDue} decides and words it; the read of the player's position
     * is the game's.
     */
    @GrugGenerated(
            "the report is due only after a joined player has waited five seconds for the world,"
                    + " which depends on how long the client takes to load; the decision and the"
                    + " text are core's tested GrugServerJoin.waitReportDue and waitMessage")
    private static void reportWaiting(List<?> players) {
        long now = System.currentTimeMillis();
        if (!GrugServerJoin.waitReportDue(!players.isEmpty(), now, lastReportMillis)) {
            return;
        }
        lastReportMillis = now;
        PlayerEntity player = (PlayerEntity) players.get(0);
        System.out.println(GrugServerJoin.waitMessage(player.x, player.boundingBox.minY, player.z));
    }

    /** Fails the run because the starter left it, which only a failing run reaches. */
    @GrugGenerated(
            "it disconnects the starter in core's server and ends the server process through stop,"
                    + " with the run failed: a test that reached it would end the very run it is"
                    + " part of; the text is core's tested GrugServerJoin.everyoneLeftReport")
    private static void failPlayerLeft() {
        GrugControlStation.server().onDisconnect(GrugControlStation.server().starter());
        GrugServerJoin.print(System.out, GrugServerJoin.everyoneLeftReport());
        stop(1);
    }

    /** Says what stopped the run when a tick threw, then ends the process with the run failed. */
    @GrugGenerated(
            "it ends the server process through stop with the run failed: a test that reached it"
                    + " would end the very run it is part of; the catch that hands it the throwable"
                    + " is core's tested GrugServerRunArms.guard")
    private static void failThrown(Throwable thrown) {
        // The server loop would end the process without a word, which reads as a hang to whoever
        // watches the log, so say what stopped the run.
        System.out.println("[GRUG CI] FAIL the server run threw " + thrown);
        thrown.printStackTrace(System.out);
        stop(1);
    }

    /** Ends the run once the runner has finished, passed or failed. */
    @GrugGenerated(
            "it runs after the last coverage segment was cut, because the run ends with the last"
                    + " test, and it ends the server process")
    private static void endRun() {
        stop(runner.isPassed() ? 0 : 1);
    }

    /** Ends the server process; a non-zero status says the run did not pass. */
    @GrugGenerated(
            "it runs after the last coverage segment was cut and ends the server process, so no"
                    + " exec data can hold a line of it")
    private static void stop(int status) {
        done = true;
        GrugControlStation.server().endRun();
        HOLD_LOG.printSummary(System.out);
        System.out.flush();
        System.exit(status);
    }

    /**
     * Whether this tick waits for the joined client, whose connection still holds more of what the
     * server queued than core's low-water mark (#1802).
     *
     * <p>The count is the game's own, so the wait starts well below the 1 MiB at which the game
     * drops the player, and core's {@code GrugSendBuffer} bounds it, so a client that stopped
     * reading reaches that drop rather than hanging the run. Only a CI run paces: a server that
     * players join outside the harness drops a client that far behind, and freezing its world for
     * the others would be worse.
     *
     * <p>The two lines are the only record that the harness chose to wait, which is what tells a
     * slow tick apart from a client that fell behind.
     */
    private static boolean waitForClient() {
        if (!"true".equals(System.getenv("GRUG_CI"))) {
            return false;
        }
        Object starter = GrugControlStation.server().starter();
        int queued = GrugSendQueue.queuedBytes((Connection) starter);
        if (!SEND_BUFFER.pause(queued)) {
            if (waitTicks > 0) {
                System.out.println(
                        "[GRUG CI] The joined client caught up after "
                                + waitTicks
                                + " ticks, with "
                                + queued
                                + " bytes still to write.");
                waitTicks = 0;
            }
            return false;
        }
        waitTicks++;
        if (waitTicks == 1) {
            System.out.println(
                    "[GRUG CI] The joined client is behind: its connection holds "
                            + queued
                            + " bytes, so the world waits for it.");
        }
        return true;
    }
}
