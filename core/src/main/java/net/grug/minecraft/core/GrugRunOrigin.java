package net.grug.minecraft.core;

import net.grug.minecraft.grug.Vec3;

/**
 * Prints the origin a run builds its rooms around, once per JVM (#1161).
 *
 * <p>The caller hands it the origin it has already snapped and built from, so no game state is held
 * here: the guard is what keeps one server log to one line. It lives in core rather than in each
 * loader because the run prints it during its first test, which is usually some mod's rather than
 * {@code coverage}'s, so that test's segment is dropped and the print in a loader was a line the
 * gate counted as missed. Core's own unit test covers both answers, and a loader only calls this on
 * every runner tick, so the call itself falls in a counted segment.
 */
public final class GrugRunOrigin {
    // No initializer: this class then needs no static initializer, whose coverage would depend on
    // which test first used it.
    private static boolean reported;

    private GrugRunOrigin() {}

    /** Prints {@code origin} the first time and nothing after it. */
    public static void reportOnce(Vec3 origin) {
        if (reported) return;
        reported = true;
        System.out.println("[GRUG CI] Test origin is " + origin + ".");
    }
}
