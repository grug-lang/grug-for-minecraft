package net.grug.minecraft.core;

import java.util.function.LongSupplier;

/**
 * Runs a loader's light recomputation over the test room until it stops changing anything.
 *
 * <p>1.2.5 lights the world incrementally, one changed block at a time, and the light it ends with
 * depends on the order of the changes it saw: after the room was rebuilt at another radius and then
 * at the default one again, the same default room held two different light fields, and the far
 * ceiling and wall sat one or two light levels darker in one than in the other. Darker by a level
 * or two is a few levels of a dark texture, which is what made a screenshot of the world behind an
 * open inventory differ between runs (#961).
 *
 * <p>The field the room's blocks define has exactly one stable value: every cell is the larger of
 * its own emission and its brightest neighbour minus one. Asking the engine to recompute every cell
 * of the room until a whole sweep changes nothing therefore ends at that one value, whatever
 * history the engine started from.
 */
public final class GrugRelight {
    private GrugRelight() {}

    /**
     * Sweeps until two sweeps in a row leave the same light behind.
     *
     * @param sweep recomputes every cell of the room once and returns a hash of the light the room
     *     holds afterwards
     * @param limit the most sweeps to try; the light of a room this size settles within two or
     *     three, so reaching it means the sweep is not converging, which no test can work around
     * @return how many sweeps ran
     * @throws IllegalStateException when {@code limit} sweeps did not settle
     */
    public static int sweepUntilStable(LongSupplier sweep, int limit) {
        long previous = sweep.getAsLong();
        for (int sweeps = 2; sweeps <= limit; sweeps++) {
            long hash = sweep.getAsLong();
            if (hash == previous) {
                return sweeps;
            }
            previous = hash;
        }
        throw new IllegalStateException(
                "the room's light did not settle in " + limit + " sweeps of recomputing it");
    }
}
