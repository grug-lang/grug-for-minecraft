package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

/**
 * Reports a defect in the mods tree, which is the err severity: it reaches the player in chat and
 * fails the run.
 *
 * <p>A mod-tree defect is one grug can describe exactly, which is what separates it from an
 * invariant: the file is named, the rule it broke is named, and the game keeps running because
 * nothing undefined is left behind. That is the same treatment {@link GrugFileIndex} gives a test
 * file outside its mod's {@code tests/}, and the same one a failed assertion gets. Anything the
 * code cannot describe is not this: it is an invariant, and it goes through {@link Grug#fatal}
 * instead.
 *
 * <p>The {@code [GRUG CI] FAIL} line is what {@code run-loader.sh} fails the run on, and the queue
 * entry is what every loader drains into chat as a red message.
 */
public final class GrugModTreeDefect {

    @GrugGenerated("utility class: never instantiated")
    private GrugModTreeDefect() {}

    /** Reports {@code message} through whichever channels a running game has. */
    public static void report(String message) {
        reportToRunAndPlayer(message, GrugCore.getAdapter());
    }

    /**
     * Visible for tests: takes the adapter rather than reading the global one, so a Java test can
     * cover the channel a game bootstrap would have installed.
     */
    static void reportToRunAndPlayer(String message, ModLoaderAdapter adapter) {
        if (adapter != null) {
            adapter.logError(message);
        }
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.add(message);
        }
        System.out.println("[GRUG CI] FAIL " + message);
    }
}
