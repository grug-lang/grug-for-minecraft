package net.grug.minecraft.forge125;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.server.FMLServerHandler;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugForge125Arms;
import net.grug.minecraft.core.GrugLinkArms;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugServerJoin;
import net.grug.minecraft.core.GrugStarterPlacement;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.forge125.block.entity.GrugSavedDataPacket;
import net.grug.minecraft.forge125.block.entity.GrugSavedDataServer125;
import net.grug.minecraft.forge125.control.GrugControlFind;
import net.grug.minecraft.forge125.control.GrugControlPacket;
import net.grug.minecraft.forge125.control.GrugControlServer125;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.src.EntityPlayerMP;
import net.minecraft.src.Packet;
import net.minecraft.src.PlayerManager;
import net.minecraft.src.World;

import java.util.EnumSet;
import java.util.List;

/**
 * Drives the test runner on a 1.2.5 dedicated server, from the end of the server's own tick. The
 * client drives it from its tick in {@code GrugClientHooks}, and a dedicated server has no client,
 * so without this nothing there starts a suite. See #166.
 *
 * <p>FML hands over the tick: {@code PatchServer} makes the server's tick call {@code
 * FMLServerHandler.onPreServerTick} and {@code onPostServerTick}, which reach a registered {@link
 * ITickHandler}. The run starts once a player is world-ready, and the deadline keeps a client that
 * never connects from hanging it. When the run ends, passed or failed, the process exits, which is
 * what ends the scripts watching it.
 */
public final class GrugServerRun implements ITickHandler {
    /** How long to wait for a client to join before failing the run. */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = GrugServerJoin.timeoutMillis();

    private static final EnumSet<TickType> TICKS = EnumSet.of(TickType.GAME);

    private static GrugTestRunner runner = null;
    private static long waitStartMillis = 0;
    private static long lastReportMillis = 0;
    private static boolean done = false;
    private static boolean advancing = false;
    private static int gameSpeed = 1;

    /**
     * Where the test save's own player stands, as feet: the position a client-only run's room is
     * built around. A joined player starts from the world's random spawn, and the renderer's single
     * precision arithmetic and the terrain's light differ at another absolute position, so the
     * server places the joined player here before the run starts (#1213).
     */
    private static final double START_X = 37.5;

    private static final double START_FEET_Y = 70.0;
    private static final double START_Z = 24.5;

    /** The player as the snap found them, so the end of the test can put them back (#1161). */
    private static final GrugTestPlayerSnap.ServerSave SNAP =
            new GrugTestPlayerSnap.ServerSave(START_X, START_Z);

    /** Remembers the starter placed at the start above, so each new starter is placed once. */
    private static final GrugStarterPlacement STARTER_PLACEMENT = new GrugStarterPlacement();

    private static double holdY = 0;
    private static int healedHealth = 0;
    private static EntityPlayerMP testPlayer = null;
    private static final double HOLD_TOLERANCE = 0.25;

    /** How many times the hold put the player back on the floor, and when it last said so. */
    private static final GrugPlayerFloor.HoldLog HOLD_LOG = new GrugPlayerFloor.HoldLog();

    /**
     * Whether the floor hold has already moved the player in this server tick. The start-height
     * hold is skipped for the rest of the tick when it has, because a faster game runs several
     * steps, and the floor hold lifting the player in one step and the start-height hold pulling
     * them back in the next would leave them where they started the tick.
     */
    private static boolean heldOnFloorThisTick = false;

    /** Registers the run's tick with FML, on a server. Called by reflection from mod_Grug. */
    public static void install() {
        GrugControlPacket.register();
        GrugControlServer125.install();
        GrugSavedDataPacket.register();
        GrugScreenPacket.register();
        GrugSavedDataSync.install(new GrugSavedDataServer125());
        cpw.mods.fml.common.FMLCommonHandler.instance().registerTickHandler(new GrugServerRun());
    }

    /**
     * Sends {@code packet} to the players in {@code world}, and to no one else: the packet carries
     * a position, and a client in another dimension applies it to whatever block sits at those
     * coordinates in its own world.
     */
    public static void sendToWorld(World world, Packet packet) {
        for (Object element : players()) {
            EntityPlayerMP player = (EntityPlayerMP) element;
            GrugLinkArms.deliverIf(
                    GrugForge125Arms.both(
                            world.equals(player.worldObj),
                            GrugLinkArms.known(player.playerNetServerHandler)),
                    player,
                    found -> found.playerNetServerHandler.sendPacket(packet));
        }
    }

    private static MinecraftServer server() {
        return FMLServerHandler.instance().getServer();
    }

    /** The overworld, or null before the server has loaded it. */
    public static World world() {
        return server().worldMngr[0];
    }

    /**
     * Sends the block changes the player managers have queued, which the server only does at the
     * end of its own tick: a frozen tick never reaches it (#1040).
     */
    public static void flushBlockChanges() {
        // The manager is private and its accessor too, so it is found by type.
        PlayerManager[] managers =
                GrugForge125Arms.required(
                        GrugControlFind.byType(server().configManager, PlayerManager[].class),
                        "the server has no player managers to flush");
        for (PlayerManager manager : managers) {
            GrugLinkArms.deliver(manager, PlayerManager::updatePlayerInstances);
        }
    }

    /** Whether {@code player} is still on the server. */
    public static boolean isConnected(EntityPlayerMP player) {
        return players().contains(player);
    }

    /**
     * Whether the server's operator list holds {@code name}, which is the game's own check: {@code
     * ServerConfigurationManager.isOp} lower-cases the name and looks it up in the set {@code
     * ops.txt} fills (#1331).
     */
    public static boolean isOp(String name) {
        return server().configManager.isOp(name);
    }

    /** Adds {@code name} to the operator list, which the game saves to {@code ops.txt}. */
    public static void addOp(String name) {
        server().configManager.addOp(name);
    }

    /** Removes {@code name} from the operator list, which the game saves to {@code ops.txt}. */
    public static void removeOp(String name) {
        server().configManager.removeOp(name);
    }

    /** The first connected player, or null. */
    @GrugGenerated(
            "it answers only when no player has started the run, and every test of a run is one a"
                    + " player started, so a test cannot reach it")
    public static EntityPlayerMP firstPlayer() {
        List<?> players = players();
        return players.isEmpty() ? null : (EntityPlayerMP) players.get(0);
    }

    private static List<?> players() {
        return server().configManager.playerEntities;
    }

    /**
     * The ticks the world takes for each of the runner's, which is how the server runs a test at
     * the loader's maximum game speed (#677): the server's own loop gives one, and this runs the
     * rest before the runner's tick.
     */
    public static void setGameSpeed(int speed) {
        gameSpeed = speed;
    }

    @Override
    public void tickStart(EnumSet<TickType> types, Object... data) {}

    @Override
    public void tickEnd(EnumSet<TickType> types, Object... data) {
        GrugLinkArms.deliverIf(
                GrugForge125Arms.runs(done, advancing, "true".equals(System.getenv("GRUG_CI"))),
                this,
                run -> GrugForge125Arms.guarded(GrugServerRun::tick, GrugServerRun::failThrown));
    }

    /**
     * Ends a run whose tick threw. The server loop would end the process without a word, which
     * reads as a hang to whoever watches the log, so it says what stopped the run.
     */
    @GrugGenerated(
            "it runs only for a tick that threw, which ends the run through stop, so a test that"
                    + " reached it would end the very run it is part of; the catch itself is core's"
                    + " tested GrugForge125Arms.guarded")
    private static void failThrown(Throwable thrown) {
        System.out.println("[GRUG CI] FAIL the server run threw " + thrown);
        thrown.printStackTrace(System.out);
        stop(1);
    }

    @Override
    public EnumSet<TickType> ticks() {
        return TICKS;
    }

    @Override
    public String getLabel() {
        return "grug";
    }

    /**
     * Runs the world's own tick from inside a test's tick, in the order the server runs it: the
     * world, its lighting until it settles, then its entities.
     */
    private static void advance(net.minecraft.src.WorldServer target, int ticks) {
        advancing = true;
        try {
            for (int tick = 0; tick < ticks; tick++) {
                target.tick();
                GrugLinkArms.drain(target, target::updatingLighting);
                target.updateEntities();
            }
        } finally {
            advancing = false;
        }
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

    /**
     * Moves the server's copy of the test player to the feet position, keeping their view, through
     * the move the floor hold uses so the joined client's camera follows (#1040).
     */
    public static void moveToFeet(EntityPlayerMP player, double x, double feetY, double z) {
        GrugLinkArms.deliver(
                player,
                found ->
                        found.playerNetServerHandler.teleportTo(
                                x, feetY, z, found.rotationYaw, found.rotationPitch));
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
     * motionY} so the fall does not continue. The top is the block's collision shape, not its full
     * cube, so a player on a slab is put on the slab's top at 70.5 rather than half a block above
     * it (#1102).
     *
     * <p>It runs before readiness is asked and before each runner tick. It does nothing while a
     * rebuild has cleared the floor and the player is falling, because there is no floor to put
     * them on yet. No host function moves the player down, so holding the feet at the floor cannot
     * hide a test's own movement. A move here also stops the start-height hold from moving the
     * player again for the rest of this server tick, since a faster game runs several steps in one.
     */
    private static void holdOnFloor(EntityPlayerMP player) {
        GrugPlayerFloor.BlockSource blocks = Grug125ServerAdapter.solidBlocks(world());
        double feet = player.boundingBox.minY;
        putBackOnFloor(
                player, feet, GrugPlayerFloor.floorTop(blocks, player.posX, feet, player.posZ));
    }

    /**
     * The arm of the floor hold that puts the player back. The decision to enter it is core's
     * {@link GrugPlayerFloor#below}, and the count, the throttle and the text of its report are
     * core's {@link GrugPlayerFloor.HoldLog}, which core's unit tests cover.
     */
    @GrugGenerated(
            "it moves the player only when the joined client has fallen into the ground, which the"
                + " client's own collision prevents and a test cannot make it do; the decision is"
                + " core's tested GrugPlayerFloor.below and the log is core's tested HoldLog"
                + " (#1136)")
    private static void putBackOnFloor(EntityPlayerMP player, double feet, double floor) {
        if (!GrugPlayerFloor.below(feet, floor)) {
            return;
        }
        // This version's teleport takes the entity position, and a server player's collision box
        // bottom sits a fixed distance below it, so the move is applied as that same difference
        // rather than as a feet height.
        double target = player.posY + (floor - feet);
        player.playerNetServerHandler.teleportTo(
                player.posX, target, player.posZ, player.rotationYaw, player.rotationPitch);
        player.motionY = 0;
        heldOnFloorThisTick = true;
        HOLD_LOG.record(System.out, floor, feet, System.currentTimeMillis());
    }

    /**
     * Snaps the server's copy of the test player to the centre of the block they stand in on x and
     * z, with yaw and pitch at 0, before the runner builds the room (#1161).
     *
     * <p>A client-only run's player is snapped by the adapter, so its room, origin and fixtures sit
     * on the block grid with a level view; a dedicated server skipped that and built from wherever
     * the joined client stood. The height is left to {@link #holdPlayer}, which owns the floor, and
     * the player is not moved again after the build. This version's teleport takes the entity
     * position, which is what {@code posX}/{@code posY}/{@code posZ} hold here, and it sends the
     * packet that carries the joined client's camera and view with the player. The player as they
     * were is saved first, so {@link #restoreTestPlayer} can put them back.
     */
    public static void snapToBlockCentre(EntityPlayerMP player) {
        GrugLinkArms.deliver(
                player,
                found -> {
                    SNAP.save(
                            found,
                            found.posX,
                            found.posY,
                            found.posZ,
                            found.rotationYaw,
                            found.rotationPitch);
                    found.playerNetServerHandler.teleportTo(
                            SNAP.snappedX(),
                            SNAP.snappedY(),
                            SNAP.snappedZ(),
                            SNAP.snappedYaw(),
                            SNAP.snappedPitch());
                });
    }

    /**
     * Puts the server's copy of the test player back where {@link #snapToBlockCentre} found them,
     * with the view they had, so a test that moved the player leaves nothing behind for the next
     * one to inherit. The runner calls this when every test ends, whether it passed, failed or
     * aborted.
     *
     * <p>The teleport sends the packet, so the joined client's camera and view go back with the
     * player. A server whose snap did not run, because it had no player copy yet, restores nothing.
     */
    public static void restoreTestPlayer(EntityPlayerMP player) {
        GrugLinkArms.deliverIf(
                SNAP.claimRestore(player),
                player,
                found -> {
                    found.playerNetServerHandler.teleportTo(
                            SNAP.savedX(),
                            SNAP.savedY(),
                            SNAP.savedZ(),
                            SNAP.savedYaw(),
                            SNAP.savedPitch());
                    SNAP.clear();
                });
    }

    /**
     * Keeps the test player where the tests expect them: at the height they landed at, and alive.
     *
     * <p>The client owns the player's movement, and each test clears and rebuilds the floor under
     * them, so a frame in which the client sees air lets them drop a block or two. Test after test
     * that adds up to a fall out of the world, and every test builds its room from where the player
     * stands. The player is put back whenever they are lower than they landed. That is safe because
     * no host function moves the player down: a snap (Grug125Adapter#snapPlayer) keeps the player's
     * height, and nothing else sets their position or motion. A test that needs the player moved
     * down has to revisit this, for instance by moving the height held.
     *
     * <p>The same drops and the blocks of a room built around the player hurt them (the scratch run
     * that found this saw the health fall from 20 to below 0 over the suite, as suffocation and
     * falls added up), and no test reads the player's health, so they are healed before each runner
     * tick. A player who is dead already, or who is no longer the one the run started with, fails
     * the run with a message instead of letting every later test fail in a way that hides the
     * cause.
     */
    private static void holdPlayer(EntityPlayerMP player) {
        failIfPlayerLost(player);
        healedHealth += GrugForge125Arms.healed(player.getHealth(), player.getMaxHealth());
        player.setEntityHealth(player.getMaxHealth());
        player.fallDistance = 0;
        holdOnFloor(player);
        holdAtStartHeight(player);
    }

    /**
     * Fails the run when the test player died or is no longer the one it started with, which only a
     * failing run reaches.
     */
    @GrugGenerated(
            "it ends the server process through stop, with the run failed, when a test player"
                    + " died or was replaced, and no test can make that happen without ending the"
                    + " very run it is part of; the decision is core's tested"
                    + " GrugForge125Arms.playerLost")
    private static void failIfPlayerLost(EntityPlayerMP player) {
        if (!GrugForge125Arms.playerLost(player.isDead, player.getHealth(), player == testPlayer)) {
            return;
        }
        System.out.println("[GRUG CI] FAIL the test player is gone");
        System.out.println(
                "[GRUG CI] The test player died or was replaced during the run (health "
                        + player.getHealth()
                        + ", dead "
                        + player.isDead
                        + "), so the tests after this point would not run in a world the"
                        + " runner built.");
        stop(1);
    }

    /**
     * Puts the player back at the height the run started at when the floor hold did not move them
     * this tick and they are lower than they landed.
     */
    @GrugGenerated(
            "it moves the player only when the joined client has dropped below the height it landed"
                    + " at, which the client's own collision prevents and a test cannot make it do"
                    + " (#1136)")
    private static void holdAtStartHeight(EntityPlayerMP player) {
        if (heldOnFloorThisTick || player.posY >= holdY - HOLD_TOLERANCE) {
            return;
        }
        player.playerNetServerHandler.teleportTo(
                player.posX, holdY, player.posZ, player.rotationYaw, player.rotationPitch);
        player.motionY = 0;
    }

    /** The feet height the hold keeps the test player at, 0 before the run has started. */
    static double heldFeetY() {
        return GrugForge125Arms.heldFeet(
                holdY, () -> testPlayer.posY - testPlayer.boundingBox.minY);
    }

    private static void tick() {
        // The multiplayer scripts write spawn-animals=false so that no animal spawns by itself, and
        // WorldServer.updateEntityWithOptionalForce then calls setDead on every EntityAnimal the
        // moment it is updated, which kills the pigs a test spawns on its first update (the
        // mobcap overlay_labels test saw its passive count go from 17 to 0). The worlds' own flag
        // that stops natural spawning was set from the property at load and is not read from here,
        // so raising the server's flag keeps natural spawns off and lets a spawned animal live.
        server().spawnPeacefulMobs = true;
        List<?> players = players();
        pollModFiles();
        heldOnFloorThisTick = false;

        if (runner == null && !awaitStart(players)) {
            return;
        }
        failIfPlayerLeft(players);

        // The runner ticks once per world tick, as the client's does at speed: a server tick has
        // run the world once already, and each extra tick of a faster game is a world tick followed
        // by a runner tick (#163).
        for (int step = 0; step < gameSpeed; step++) {
            if (step > 0) {
                advance(server().worldMngr[0], 1);
            }
            holdPlayer(testPlayer);
            // Applies what the joined client sent during the previous tick's calls, which here is a
            // forwarded click's packet (#1094). The game's own tick reads the connection too, but
            // only after this whole game-speed loop, and a test asserts on the tick after its
            // click.
            GrugControlServer125.server().applyClientWork();
            runner.tick(testPlayer);
            if (runner.roomBuiltThisTick()) return;
            finishIfDone();
        }
    }

    /**
     * Waits for the run to start, and starts it when a player has asked for it and the world around
     * them is ready.
     *
     * @return whether the runner exists now and this tick goes on to run it
     */
    private static boolean awaitStart(List<?> players) {
        waitStartMillis = GrugForge125Arms.startedAt(waitStartMillis, System.currentTimeMillis());
        // The run starts when a player has started it (#1040): the joined client sends its
        // start once it is in the world. The player is held on the server's own floor before
        // readiness is asked, so a client that reported a position a fraction inside the ground
        // can land and be accepted (#1136).
        EntityPlayerMP starter = GrugControlServer125.starterPlayer();
        if (starter != null) {
            holdOnFloor(starter);
            if (STARTER_PLACEMENT.shouldPlace(starter)) {
                // The teleport takes the entity position, so the move is the feet's difference.
                starter.playerNetServerHandler.teleportTo(
                        START_X,
                        starter.posY + (START_FEET_Y - starter.boundingBox.minY),
                        START_Z,
                        0.0F,
                        0.0F);
                starter.motionY = 0;
                System.out.println(
                        "[GRUG CI] placed the test player at the save's start "
                                + START_X
                                + ", "
                                + START_FEET_Y
                                + ", "
                                + START_Z);
                return false;
            }
        }
        if (!GrugForge125Arms.ready(
                !players.isEmpty(),
                GrugControlServer125.server().hasStarter(),
                starter != null,
                () -> GrugCore.getAdapter().isWorldReady(starter))) {
            failJoinTimeoutIfDue();
            reportWaiting(players);
            return false;
        }
        System.out.println(
                "[GRUG CI] CI mode active & player loaded after "
                        + (System.currentTimeMillis() - waitStartMillis)
                        + " ms. Firing tests!");
        testPlayer = starter;
        holdY = testPlayer.posY;
        runner = new GrugTestRunner();
        return true;
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
     * Says, at most every five seconds, that the run is still waiting for the world around a joined
     * player.
     */
    @GrugGenerated(
            "it prints only when a player has been here for over five seconds before the world is"
                    + " ready, a timing no test can force; the decision and the text are core's"
                    + " tested GrugServerJoin.waitReportDue and waitMessage")
    private static void reportWaiting(List<?> players) {
        long now = System.currentTimeMillis();
        if (!GrugServerJoin.waitReportDue(!players.isEmpty(), now, lastReportMillis)) {
            return;
        }
        lastReportMillis = now;
        EntityPlayerMP player = (EntityPlayerMP) players.get(0);
        System.out.println(
                GrugServerJoin.waitMessage(player.posX, player.boundingBox.minY, player.posZ));
    }

    /** Fails the run when the test player is no longer on the server. */
    @GrugGenerated(
            "it ends the server process through stop, with the run failed: a test that reached it"
                    + " would end the very run it is part of; the decision is core's tested"
                    + " GrugForge125Arms.playerGone and the text is core's tested"
                    + " GrugServerJoin.everyoneLeftReport")
    private static void failIfPlayerLeft(List<?> players) {
        if (!GrugForge125Arms.playerGone(players, testPlayer)) {
            return;
        }
        GrugControlServer125.server().onDisconnect(GrugControlServer125.server().starter());
        GrugServerJoin.print(System.out, GrugServerJoin.everyoneLeftReport());
        stop(1);
    }

    /** Ends the run once its runner has finished, passed or failed. */
    @GrugGenerated(
            "it runs after the last coverage segment is written (GrugCoverage.runPassed), so"
                    + " nothing in it can be counted; the process ends in it")
    private static void finishIfDone() {
        if (runner.isFinished()) {
            stop(runner.isPassed() ? 0 : 1);
        }
    }

    /** Ends the server process; a non-zero status says the run did not pass. */
    @GrugGenerated(
            "it ends the server process after the last coverage segment is written, so nothing in"
                    + " it can be counted")
    private static void stop(int status) {
        done = true;
        GrugControlServer125.server().endRun();
        System.out.println(
                "[GRUG CI] The test player was healed " + healedHealth + " health in all.");
        HOLD_LOG.printSummary(System.out);
        System.out.flush();
        System.exit(status);
    }
}
