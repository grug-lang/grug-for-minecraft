package net.grug.minecraft.bootstrap;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.util.function.Predicate;

/**
 * The outer fabric.mod.json's entrypoint, which Fabric Loader runs on every game in the Fabric
 * family that reads the universal jar. Each nested layer has the mod id {@code grug} and activates
 * only on its own game, so when none loaded, this game has no layer, and grug reports that (#493).
 * Java 8, since the Fabric family reaches back to Alpha on Java 8.
 */
public class GrugFabricPreLaunch implements PreLaunchEntrypoint {
    private final Predicate<String> modLoaded;

    public GrugFabricPreLaunch() {
        this(id -> FabricLoader.getInstance().isModLoaded(id));
    }

    GrugFabricPreLaunch(Predicate<String> modLoaded) {
        this.modLoaded = modLoaded;
    }

    @Override
    public void onPreLaunch() {
        if (!modLoaded.test(GrugForgeMod.MOD_ID)) {
            throw GrugUnsupportedGame.error("this game");
        }
    }
}
