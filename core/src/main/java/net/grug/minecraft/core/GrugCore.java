package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;
import java.io.File;

public class GrugCore {
    private static ModLoaderAdapter adapter;

    public static void initialize(ModLoaderAdapter loaderAdapter, File modApiJson, File grugModsDir) {
        adapter = loaderAdapter;
        Grug.init(modApiJson, grugModsDir);
    }

    public static ModLoaderAdapter getAdapter() {
        return adapter;
    }
}
