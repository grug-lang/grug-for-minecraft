package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModFileStates;
import net.grug.minecraft.grug.GrugModModes;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class GrugCore {
    private static ModLoaderAdapter adapter;

    @GrugGenerated("utility class: never instantiated")
    private GrugCore() {}

    public static void initialize(
            ModLoaderAdapter loaderAdapter, File modApiJson, File grugModsDir) {
        adapter = loaderAdapter;

        // Ahead of everything else, because the side decides what the level and player handles can
        // answer. A loader that cannot report one would fail much later as a null the mod sees as a
        // missing player, which names the symptom rather than the cause.
        if (adapter.getSide() == null) {
            throw Grug.fatal("The loader adapter did not report which side grug is running on.");
        }

        // Ahead of everything that reads the mods tree, because a run that was killed partway
        // through a hot-reload test left the file it was editing in a test state, and the
        // compiler's complaint about that file names a stray character rather than the run that
        // caused it. Putting the file back here means the next start compiles the real thing.
        try {
            // The log consumer is a lambda rather than a method reference so that it tolerates a
            // null adapter, which is how a Java test drives the rest of this method.
            GrugModFileStates.restoreInterruptedRuns(
                    grugModsDir, message -> loaderAdapter.logInfo(message));
        } catch (IOException e) {
            // Stopping here rather than carrying on, because carrying on means compiling the
            // disturbed file and reporting the compiler's complaint about it.
            throw new IllegalStateException(
                    "Could not restore the mod files an earlier run left in a test state.", e);
        }

        // Every mod must ship its license text, and a missing one is a hard error rather than a
        // warning: grug persists and redistributes mods, so a mod without its license is not a
        // state the game is willing to start in.
        List<String> licenseErrors = GrugModLicenses.validate(grugModsDir);
        if (!licenseErrors.isEmpty()) {
            throw Grug.fatal(
                    "Every mod must ship its license text as a LICENSE file next to its"
                            + " about.json:\n  "
                            + String.join("\n  ", licenseErrors));
        }

        // Read alongside the licenses because it is the same kind of promise about a mod: an exact
        // port is only meaningful if a test has to honour it, so whether each mod is one
        // has to be known before any test runs.
        GrugExactPorts.load(grugModsDir);

        // And so is a mod's mode: a mod that asks to run unsafe only does so with the player's
        // grant, and every file of the mod has to compile in that mode, so the mode has to be
        // known before anything of the mod compiles (#334).
        GrugModModes.load(grugModsDir, adapter.getGameDirectory());

        Grug.init(modApiJson, grugModsDir);
    }

    public static ModLoaderAdapter getAdapter() {
        return adapter;
    }
}
