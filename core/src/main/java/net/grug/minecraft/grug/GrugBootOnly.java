package net.grug.minecraft.grug;

import java.io.PrintStream;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Lets CI end a run as soon as grug has loaded, for a platform that cannot be given what a whole
 * run needs, such as the OpenGL a window and the screenshot goldens call for.
 *
 * <p>When {@code GRUG_CI_BOOT_ONLY} is set, the game stops with status 0 right after the mods have
 * compiled, having printed {@link #MESSAGE}, which run-loader.sh looks for. By then the native has
 * loaded, the VM has started and the mods directory has been read, which is everything specific to
 * grug on the platform, and it happens before the game opens a window.
 */
final class GrugBootOnly {
    /** The line run-loader.sh waits for in a boot-only run. */
    static final String MESSAGE = "[GRUG CI] GRUG LOADED";

    private GrugBootOnly() {}

    /** Ends the run if this one asked to be boot-only. */
    static void finish() {
        finish(System::getenv, System.out, System::exit);
    }

    static void finish(Function<String, String> env, PrintStream out, IntConsumer exit) {
        if (env.apply("GRUG_CI_BOOT_ONLY") == null) return;
        out.println(MESSAGE);
        out.flush();
        exit.accept(0);
    }
}
