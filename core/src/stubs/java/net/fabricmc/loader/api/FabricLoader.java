package net.fabricmc.loader.api;

/**
 * Compile-time stand-in for Fabric Loader's API, of which GrugFabricPreLaunch asks whether a mod
 * loaded. Tests run against it, where no Fabric runs, so no mod is loaded. Never packaged.
 */
public interface FabricLoader {
    static FabricLoader getInstance() {
        return id -> false;
    }

    boolean isModLoaded(String id);
}
