package net.grug.minecraft.forge125;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.server.FMLServerHandler;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.src.EntityPlayerMP;
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
    private static final double HOLD_TOLERANCE = 0.25;

    /** Registers the run's tick with FML, on a server. Called by reflection from mod_Grug. */
    public static void install() {
        cpw.mods.fml.common.FMLCommonHandler.instance().registerTickHandler(new GrugServerRun());
    }

    private static MinecraftServer server() {
        return FMLServerHandler.instance().getServer();
    }

    /** The overworld, or null before the server has loaded it. */
    public static World world() {
        MinecraftServer server = server();
        return server == null || server.worldMngr == null ? null : server.worldMngr[0];
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
     * Puts the player back at the height they landed at when they fell below it. The client owns
     * the player's movement, and each test clears and rebuilds the floor under them, so a frame in
     * which the client sees air lets them drop a block or two; test after test that adds up to a
     * fall out of the world, and every test builds its room from where the player stands.
     */
    private static void holdPlayer(EntityPlayerMP player) {
        if (player.posY < holdY - HOLD_TOLERANCE) {
            player.playerNetServerHandler.teleportTo(
                    player.posX, holdY, player.posZ, player.rotationYaw, player.rotationPitch);
            player.motionY = 0;
        }
    }

    private static void tick() {
        List<?> players = players();
        pollModFiles();

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            if (!players.isEmpty() && GrugCore.getAdapter().isWorldReady(players.get(0))) {
                System.out.println(
                        "[GRUG CI] CI mode active & player loaded after "
                                + (System.currentTimeMillis() - waitStartMillis)
                                + " ms. Firing tests!");
                holdY = ((EntityPlayerMP) players.get(0)).posY;
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

        if (players.isEmpty()) {
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
            holdPlayer((EntityPlayerMP) players.get(0));
            runner.tick(players.get(0));
            if (runner.isFinished()) {
                stop(runner.isPassed() ? 0 : 1);
                return;
            }
        }
    }

    /** Ends the server process; a non-zero status says the run did not pass. */
    private static void stop(int status) {
        done = true;
        System.out.flush();
        System.exit(status);
    }
}
