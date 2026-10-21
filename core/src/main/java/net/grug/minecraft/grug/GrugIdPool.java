package net.grug.minecraft.grug;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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

    /**
     * Whether every grug mod fits in the free ids. When they do not, the shortage is recorded for
     * the mod manager's screen (see {@link GrugIdShortage}) and the loader registers none of this
     * kind.
     *
     * <p>Run before the first id is taken, so the screen shows what all the mods need rather than
     * where registration happened to stop.
     *
     * @param modByName the mod, by the name of its directory, of each block or item that will take
     *     an id, keyed by the name registration keys it by: a name two mods ship takes one id, so
     *     it is counted once
     */
    public boolean hasRoomFor(Map<String, String> modByName) {
        int needed = modByName.size();
        if (needed <= remaining()) return true;

        Map<String, Integer> needsByMod = new HashMap<>();
        for (String mod : modByName.values()) {
            needsByMod.merge(mod, 1, Integer::sum);
        }
        GrugIdShortage.record(kind, needed, remaining(), needsByMod);
        return false;
    }
}
