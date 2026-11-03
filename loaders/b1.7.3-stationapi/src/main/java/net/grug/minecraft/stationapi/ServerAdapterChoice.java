package net.grug.minecraft.stationapi;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.GrugGenerated;

/**
 * Picks the adapter for the process the game is running as.
 *
 * <p>It is a class of its own so that {@code InitListener}, which runs on both sides, only names
 * this one: the server adapter is created in a method nothing on a client calls, and the client
 * never has to load it. Fabric Loader refuses to define a class that mentions a class the game
 * marked server-only in a client process, and the verifier does not load a class that is only
 * returned as an interface (#165).
 */
@GrugGenerated("the server arm is reached only by the dispatch-only dedicated-server job")
public final class ServerAdapterChoice {
    private ServerAdapterChoice() {}

    public static ModLoaderAdapter forThisSide() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            return new ServerStationApiAdapter();
        }
        return new StationApiAdapter();
    }
}
