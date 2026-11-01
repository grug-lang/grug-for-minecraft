package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugCore;
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

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        MinecraftServer server = event.getServer();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();

        if (runner == null) {
            if (waitStartMillis == 0) {
                waitStartMillis = System.currentTimeMillis();
            }
            if (!players.isEmpty() && GrugCore.getAdapter().isWorldReady(players.get(0))) {
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

        runner.tick(players.get(0));
        if (runner.isFinished()) {
            stop(server);
        }
    }

    private void stop(MinecraftServer server) {
        done = true;
        server.halt(false);
    }
}
