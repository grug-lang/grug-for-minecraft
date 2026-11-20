package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

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
 * its own emission and its brightest neighbour minus that cell's opacity (at least one), so an
 * opaque cell such as a stone wall stays at 0 however bright its neighbour is. Asking the engine to
 * recompute every cell of the room until a whole sweep changes nothing therefore ends at that one
 * value, whatever history the engine started from.
 *
 * <p>Both fields the game's renderer bakes a face's brightness from are part of that: beta's
 * terrain renderer takes the larger of a cell's sky and block light, so a room whose sky field was
 * left at the open sky's 15 renders at full brightness however right its block light is (#1368).
 * The engine also skips an update whose box covers more than 32768 cells, which is how a rebuilt
 * room keeps the sky it had before its walls were there, so a settle that queues and hashes only
 * the block field confirms a room that is still bright.
 */
public final class GrugRelight {
    private GrugRelight() {}

    /**
     * A room's light engine, as {@link #settle} drives it: the two fields the renderer bakes
     * brightness from, queued per box and read per cell.
     */
    public interface Room {
        /** Asks the engine to recompute the box's sky light. */
        void queueSky(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

        /** Asks the engine to recompute the box's block light. */
        void queueBlock(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);

        /** Runs every queued update to completion. */
        void drain();

        /** The sky light a cell holds now, 0 to 15, as the renderer reads it. */
        int sky(int x, int y, int z);

        /** The block light a cell holds now, 0 to 15, as the renderer reads it. */
        int block(int x, int y, int z);
    }

    /**
     * Recomputes both light fields of the box until a sweep changes neither, then returns how many
     * sweeps that took.
     *
     * @see #sweepUntilStable
     */
    public static int settle(
            Room room, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int limit) {
        return sweepUntilStable(() -> sweep(room, minX, minY, minZ, maxX, maxY, maxZ), limit);
    }

    /**
     * Recomputes the box once, for both fields, and hashes what it holds afterwards. The sky is
     * hashed as well as the block light: a sweep that only repairs the block field leaves the sky
     * field where the room was open to the sky, and a hash that ignores it calls that settled.
     */
    static long sweep(Room room, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        room.queueSky(minX, minY, minZ, maxX, maxY, maxZ);
        room.queueBlock(minX, minY, minZ, maxX, maxY, maxZ);
        room.drain();
        long hash = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    hash = hash * 31 + room.sky(x, y, z);
                    hash = hash * 31 + room.block(x, y, z);
                }
            }
        }
        return hash;
    }

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
        throw Grug.fatal(
                "the room's light did not settle in " + limit + " sweeps of recomputing it");
    }
}
