package net.grug.minecraft.forge125;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.server.FMLServerHandler;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSavedDataSync;
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
@GrugGenerated("a dedicated server, whose coverage no CI run merges")
public final class GrugServerRun implements ITickHandler {
    /** How long to wait for a client to join before failing the run. */
    static final long PLAYER_JOIN_TIMEOUT_MILLIS = 240_000;

    private static final EnumSet<TickType> TICKS = EnumSet.of(TickType.GAME);

    private static GrugTestRunner runner = null;
    private static long waitStartMillis = 0;
    private static long lastReportMillis = 0;
    private static boolean done = false;
    private static boolean advancing = false;
    private static int gameSpeed = 1;
    private static double holdY = 0;
    private static int healedHealth = 0;
    private static EntityPlayerMP testPlayer = null;
    private static final double HOLD_TOLERANCE = 0.25;

    /** Registers the run's tick with FML, on a server. Called by reflection from mod_Grug. */
    public static void install() {
        GrugControlPacket.register();
        GrugControlServer125.install();
        GrugSavedDataPacket.register();
        GrugSavedDataSync.install(new GrugSavedDataServer125());
        cpw.mods.fml.common.FMLCommonHandler.instance().registerTickHandler(new GrugServerRun());
    }

    /** Sends {@code packet} to every player who is on this server. */
    public static void sendToAllPlayers(Packet packet) {
        MinecraftServer server = server();
        if (server != null && server.configManager != null) {
            server.configManager.sendPacketToAllPlayers(packet);
        }
    }

    private static MinecraftServer server() {
        return FMLServerHandler.instance().getServer();
    }

    /** The overworld, or null before the server has loaded it. */
    public static World world() {
        MinecraftServer server = server();
        return server == null || server.worldMngr == null ? null : server.worldMngr[0];
    }

    /**
     * Sends the block changes the player managers have queued, which the server only does at the
     * end of its own tick: a frozen tick never reaches it (#1040).
     */
    public static void flushBlockChanges() {
        MinecraftServer server = server();
        if (server != null && server.configManager != null) {
            // The manager is private and its accessor too, so it is found by type.
            PlayerManager[] managers =
                    GrugControlFind.byType(server.configManager, PlayerManager[].class);
            if (managers == null) {
                throw new IllegalStateException("the server has no player managers to flush");
            }
            for (PlayerManager manager : managers) {
                if (manager != null) {
                    manager.updatePlayerInstances();
                }
            }
        }
    }

    /** Whether {@code player} is still on the server. */
    public static boolean isConnected(EntityPlayerMP player) {
        return players().contains(player);
    }

    /** The first connected player, or null. */
    public static EntityPlayerMP firstPlayer() {
        List<?> players = players();
        return players.isEmpty() ? null : (EntityPlayerMP) players.get(0);
    }

    private static List<?> players() {
        MinecraftServer server = server();
        return server == null || server.configManager == null
                ? java.util.Collections.emptyList()
                : server.configManager.playerEntities;
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
        if (done || advancing || !"true".equals(System.getenv("GRUG_CI"))) {
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
                while (target.updatingLighting()) {}
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
     *
     * @return whether the run may go on
     */
    private static boolean holdPlayer(EntityPlayerMP player) {
        if (player.isDead || player.getHealth() <= 0 || player != testPlayer) {
            System.out.println("[GRUG CI] FAIL the test player is gone");
            System.out.println(
                    "[GRUG CI] The test player died or was replaced during the run (health "
                            + player.getHealth()
                            + ", dead "
                            + player.isDead
                            + "), so the tests after this point would not run in a world the"
                            + " runner built.");
            stop(1);
            return false;
        }
        if (player.getHealth() < player.getMaxHealth()) {
            healedHealth += player.getMaxHealth() - player.getHealth();
            player.setEntityHealth(player.getMaxHealth());
        }
        player.fallDistance = 0;
        if (player.posY < holdY - HOLD_TOLERANCE) {
            player.playerNetServerHandler.teleportTo(
                    player.posX, holdY, player.posZ, player.rotationYaw, player.rotationPitch);
            player.motionY = 0;
        }
        return true;
    }

    private static void tick() {
        List<?> players = players();
        pollModFiles();

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            // The run starts when a player has started it (#1040): the joined client sends its
            // start once it is in the world.
            EntityPlayerMP starter = GrugControlServer125.starterPlayer();
            if (!players.isEmpty()
                    && GrugControlServer125.server().hasStarter()
                    && starter != null
                    && GrugCore.getAdapter().isWorldReady(starter)) {
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Firing tests!");
                testPlayer = starter;
                holdY = testPlayer.posY;
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
                    EntityPlayerMP player = (EntityPlayerMP) players.get(0);
                    System.out.println(
                            "[GRUG CI] waiting for the world: player at "
                                    + player.posX
                                    + ", "
                                    + player.boundingBox.minY
                                    + ", "
                                    + player.posZ);
                }
                return;
            }
        }

        if (players.isEmpty() || !players.contains(testPlayer)) {
            GrugControlServer125.server().onDisconnect(GrugControlServer125.server().starter());
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
        for (int step = 0; step < gameSpeed; step++) {
            if (step > 0) {
                advance(server().worldMngr[0], 1);
            }
            if (!holdPlayer(testPlayer)) {
                return;
            }
            runner.tick(testPlayer);
            if (runner.isFinished()) {
                stop(runner.isPassed() ? 0 : 1);
                return;
            }
        }
    }

    /** Ends the server process; a non-zero status says the run did not pass. */
    private static void stop(int status) {
        done = true;
        GrugControlServer125.server().endRun();
        System.out.println(
                "[GRUG CI] The test player was healed " + healedHealth + " health in all.");
        System.out.flush();
        System.exit(status);
    }
}
