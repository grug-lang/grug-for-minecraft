package net.grug.minecraft.grug;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
    private final int first;
    private final int last;
    private final int[] free;
    private int next = 0;

    private GrugIdPool(String kind, int first, int last, int[] free) {
        this.kind = kind;
        this.first = first;
        this.last = last;
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
        return new GrugIdPool(kind, first, last, freeIds(taken, first, last));
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
     * Stops the game with an error that tells the player what to turn off, unless every grug mod
     * fits in the free ids.
     *
     * <p>Run before the first id is taken, so the game stops with all the mods' needs known rather
     * than at whichever block happened to be last. The error lists every mod with the ids it needs,
     * the most first and ties by name, and not only the mods that missed out: which ones those are
     * depends on the order they register in, and a player is as well served by turning off a mod
     * that uses a lot of ids. The error also gives the exact way to turn one off by hand.
     *
     * @param modByName the mod, by the name of its directory, of each block or item that will take
     *     an id, keyed by the name registration keys it by: a name two mods ship takes one id, so
     *     it is counted once
     * @param modsDirectory the directory the mods are installed in
     */
    public void requireRoom(Map<String, String> modByName, File modsDirectory) {
        Map<String, Integer> needsByMod = new HashMap<>();
        for (String mod : modByName.values()) {
            needsByMod.merge(mod, 1, Integer::sum);
        }
        int needed = modByName.size();
        if (needed <= remaining()) return;

        throw Grug.fatal(shortage(needsByMod, needed, modsDirectory));
    }

    private String shortage(Map<String, Integer> needsByMod, int needed, File modsDirectory) {
        List<Map.Entry<String, Integer>> mods = new ArrayList<>(needsByMod.entrySet());
        mods.sort(
                (a, b) -> {
                    int byCount = Integer.compare(b.getValue(), a.getValue());
                    return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                });

        int missing = needed - remaining();
        StringBuilder message = new StringBuilder();
        message.append("Not enough free ").append(kind).append(" ids for grug's ").append(kind);
        message.append("s: they need ").append(needed).append(" and ").append(remaining());
        message.append(" are free among the ids ").append(first).append(" to ").append(last);
        message.append(", so ").append(missing).append(" ids are short.");
        message.append("\nThe ").append(kind).append(" ids each grug mod needs, most first:");
        for (Map.Entry<String, Integer> mod : mods) {
            message.append("\n  ").append(mod.getKey()).append(": ").append(mod.getValue());
        }
        message.append("\nTurn off grug mods that need at least ").append(missing);
        message.append(" ")
                .append(kind)
                .append(" ids together: close the game, then move the mod's");
        message.append(" directory out of ").append(modsDirectory).append(", for example ");
        message.append(new File(modsDirectory, mods.get(0).getKey()));
        message.append(", and start the game again. grug does not put a mod you removed back.");
        return message.toString();
    }
}
