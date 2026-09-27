package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;
import java.io.File;
import java.util.List;

public class GrugCore {
    private static ModLoaderAdapter adapter;

    public static void initialize(ModLoaderAdapter loaderAdapter, File modApiJson, File grugModsDir) {
        adapter = loaderAdapter;

        // Every mod must ship its license text, and a missing one is a hard error rather than a
        // warning: grug persists and redistributes mods, so a mod without its license is not a
        // state the game is willing to start in.
        List<String> licenseErrors = GrugModLicenses.validate(grugModsDir);
        if (!licenseErrors.isEmpty()) {
            throw Grug.fatal("Every mod must ship its license text as a LICENSE file next to its"
                    + " about.json:\n  " + String.join("\n  ", licenseErrors));
        }

        Grug.init(modApiJson, grugModsDir);
    }

    public static ModLoaderAdapter getAdapter() {
        return adapter;
    }
}
