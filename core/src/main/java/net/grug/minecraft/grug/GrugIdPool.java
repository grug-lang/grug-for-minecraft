package net.grug.minecraft.grug;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * The numeric ids a game before 1.7 has left for grug's blocks or items, handed out in ascending
 * order.
 *
 * <p>Those games give blocks and items a small id space that a pack's other mods fill, so grug
 * takes the free ids and never one that is occupied. Which ids are free is a pure function of what
 * is occupied and the range searched, so every id-limited loader answers it the same way.
 */
public final class GrugIdPool {
    private final String kind;
    private final int[] free;
    private int next = 0;

    private GrugIdPool(String kind, int[] free) {
        this.kind = kind;
        this.free = free;
    }

    /**
     * The ids from {@code first} to {@code last}, both included, that {@code taken} rejects, in
     * ascending order.
     */
    public static int[] freeIds(IntPredicate taken, int first, int last) {
        int count = 0;
        int[] found = new int[Math.max(0, last - first + 1)];
        for (int id = first; id <= last; id++) {
            if (!taken.test(id)) {
                found[count++] = id;
            }
        }
        return Arrays.copyOf(found, count);
    }

    /**
     * A pool of the free ids in the range.
     *
     * @param kind what the ids are for, "block" or "item", which the exhaustion error names
     */
    public static GrugIdPool of(String kind, IntPredicate taken, int first, int last) {
        return new GrugIdPool(kind, freeIds(taken, first, last));
    }

    /** How many ids the pool has not handed out yet. */
    public int remaining() {
        return free.length - next;
    }

    /** The lowest free id not handed out yet. */
    public int take() {
        if (next == free.length) {
            throw Grug.fatal("No free " + kind + " id for a grug " + kind);
        }
        return free[next++];
    }
}
