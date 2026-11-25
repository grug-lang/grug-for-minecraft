package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 * The engine also skips an update whose box covers more than {@link #MAX_UPDATE_CELLS} cells, which
 * is how a rebuilt room keeps the sky it had before its walls were there, so a settle that queues
 * and hashes only the block field confirms a room that is still bright.
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
     * The most cells one light update may cover, which is what beta's engine accepts: its {@code
     * LightUpdate.updateLight} prints {@code Light too large, skipping!} and recomputes nothing for
     * a larger box. A radius 13 room's box is 29 x 29 x 42, which is 35322 cells, so a settle or a
     * server relight that hands that box over whole does nothing at all and confirms the light the
     * room was built over.
     */
    static final int MAX_UPDATE_CELLS = 32768;

    /**
     * One recompute of a box, as {@link #inPieces} hands it over: a loader whose engine recomputes
     * a box immediately, rather than queuing it, has no {@link Room} to drive but still has to stay
     * within the size the engine accepts.
     */
    public interface BoxUpdate {
        /** Recomputes the box. */
        void update(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    }

    /**
     * Hands the box to {@code update} as the pieces {@link #pieces} gives, so that no update is
     * larger than the engine accepts and none of them is dropped.
     */
    public static void inPieces(
            BoxUpdate update, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int[] piece : pieces(minX, minY, minZ, maxX, maxY, maxZ)) {
            update.update(piece[0], piece[1], piece[2], piece[3], piece[4], piece[5]);
        }
    }

    /**
     * Recomputes the box once, for both fields, and hashes what it holds afterwards. The sky is
     * hashed as well as the block light: a sweep that only repairs the block field leaves the sky
     * field where the room was open to the sky, and a hash that ignores it calls that settled.
     *
     * <p>Each field is queued as the pieces {@link #pieces} gives, so that no update is larger than
     * the engine accepts and none of them is dropped.
     */
    static long sweep(Room room, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        inPieces(
                (pieceMinX, pieceMinY, pieceMinZ, pieceMaxX, pieceMaxY, pieceMaxZ) -> {
                    room.queueSky(pieceMinX, pieceMinY, pieceMinZ, pieceMaxX, pieceMaxY, pieceMaxZ);
                    room.queueBlock(
                            pieceMinX, pieceMinY, pieceMinZ, pieceMaxX, pieceMaxY, pieceMaxZ);
                },
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ);
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
     * The box, as boxes the engine accepts in one update: each covers at most {@link
     * #MAX_UPDATE_CELLS} cells, together they cover exactly the box, and no two of them overlap. A
     * box that is too large is split in half along its longest side and each half is split again,
     * so the pieces differ in size by at most one block and a room needs as few updates as its size
     * allows.
     */
    static List<int[]> pieces(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        List<int[]> pieces = new ArrayList<>();
        Deque<int[]> pending = new ArrayDeque<>();
        pending.add(new int[] {minX, minY, minZ, maxX, maxY, maxZ});
        while (!pending.isEmpty()) {
            int[] box = pending.removeFirst();
            if (cells(box) <= MAX_UPDATE_CELLS) {
                pieces.add(box);
                continue;
            }
            long xSpan = (long) box[3] - box[0];
            long ySpan = (long) box[4] - box[1];
            long zSpan = (long) box[5] - box[2];
            if (xSpan >= ySpan && xSpan >= zSpan) {
                int middle = box[0] + (int) Math.floorDiv((long) box[3] - box[0], 2);
                pending.add(new int[] {box[0], box[1], box[2], middle, box[4], box[5]});
                pending.add(new int[] {middle + 1, box[1], box[2], box[3], box[4], box[5]});
            } else if (ySpan >= zSpan) {
                int middle = box[1] + (int) Math.floorDiv((long) box[4] - box[1], 2);
                pending.add(new int[] {box[0], box[1], box[2], box[3], middle, box[5]});
                pending.add(new int[] {box[0], middle + 1, box[2], box[3], box[4], box[5]});
            } else {
                int middle = box[2] + (int) Math.floorDiv((long) box[5] - box[2], 2);
                pending.add(new int[] {box[0], box[1], box[2], box[3], box[4], middle});
                pending.add(new int[] {box[0], box[1], middle + 1, box[3], box[4], box[5]});
            }
        }
        return pieces;
    }

    /** How many cells a box covers, as the engine counts them. */
    private static long cells(int[] box) {
        return ((long) box[3] - box[0] + 1)
                * ((long) box[4] - box[1] + 1)
                * ((long) box[5] - box[2] + 1);
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
