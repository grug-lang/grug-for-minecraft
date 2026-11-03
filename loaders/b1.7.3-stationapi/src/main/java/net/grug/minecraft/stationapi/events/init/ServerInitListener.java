package net.grug.minecraft.stationapi.events.init;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugIdShortage;
import net.grug.minecraft.stationapi.server.GrugServerRun;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.modificationstation.stationapi.api.event.init.InitFinishedEvent;
import net.modificationstation.stationapi.api.event.tick.GameTickEvent;
import net.modificationstation.stationapi.api.mod.entrypoint.EntrypointManager;
import net.modificationstation.stationapi.api.server.event.network.PlayerLoginEvent;

import java.lang.invoke.MethodHandles;

/**
 * The {@code stationapi:event_bus_server} entrypoint: the listeners only a dedicated server needs.
 * StationAPI loads {@link InitListener} on both sides, so the registration, the recipes and the
 * adapter are already there; this adds what drives a test run from the server's own events (#165).
 *
 * <p>A class named only here is never loaded by a client, which is what lets it mention classes the
 * game marked server-only.
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class ServerInitListener {
    static {
        EntrypointManager.registerLookup(MethodHandles.lookup());
    }

    /** A server has no screen to turn mods off on, so ids that are short end the run. */
    @EventListener
    private static void initFinished(InitFinishedEvent event) {
        GrugIdShortage.failIfShort();
    }

    /** The login is the only moment StationAPI hands over the server this player is on. */
    @EventListener
    private static void playerLoggedIn(PlayerLoginEvent event) {
        GrugServerRun.onServer(event.player.server);
    }

    /** Hands the end of each server tick to the test run. */
    @EventListener
    private static void tickEnd(GameTickEvent.End event) {
        GrugServerRun.onServerTick();
    }
}
