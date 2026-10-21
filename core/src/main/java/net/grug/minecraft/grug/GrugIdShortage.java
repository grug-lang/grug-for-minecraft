package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A kind of numeric id, block or item, that grug's mods need more of than a game before 1.7 has
 * free.
 *
 * <p>A loader that finds the ids short registers none of that kind and lets the game reach its
 * title screen, where the mod manager's screen takes over until the player turns mods off and
 * restarts (#510). This is what the loader leaves behind for that screen: how short the ids are,
 * and how many each mod needs.
 */
public final class GrugIdShortage {
    private static final List<GrugIdShortage> recorded = new ArrayList<>();

    private final String kind;
    private final int needed;
    private final int free;
    private final Map<String, Integer> needsByMod;

    private GrugIdShortage(String kind, int needed, int free, Map<String, Integer> needsByMod) {
        this.kind = kind;
        this.needed = needed;
        this.free = free;
        this.needsByMod = needsByMod;
    }

    /** Remembers that the mods need {@code needed} ids of the kind and only {@code free} are. */
    static synchronized void record(
            String kind, int needed, int free, Map<String, Integer> needsByMod) {
        recorded.add(
                new GrugIdShortage(
                        kind,
                        needed,
                        free,
                        Collections.unmodifiableMap(new HashMap<>(needsByMod))));
    }

    /** Every kind of id that is short, in the order the loader found out. */
    public static synchronized List<GrugIdShortage> all() {
        return new ArrayList<>(recorded);
    }

    /** Forgets every shortage, for a game that registers its ids again. */
    static synchronized void clear() {
        recorded.clear();
    }

    /**
     * Ends the run when an id is short, for a run no one is there to answer the screen of: the text
     * says which kinds are short and what each mod needs, most first and ties by name.
     */
    public static synchronized void failIfShort() {
        if (recorded.isEmpty()) return;
        StringBuilder message = new StringBuilder();
        for (GrugIdShortage shortage : recorded) {
            if (message.length() > 0) message.append('\n');
            message.append("Not enough free ").append(shortage.kind).append(" ids for grug's ");
            message.append(shortage.kind).append("s: they need ").append(shortage.needed);
            message.append(" and ").append(shortage.free).append(" are free, so ");
            message.append(shortage.missing()).append(" ids are short.");
            message.append("\nThe ").append(shortage.kind).append(" ids each grug mod needs:");
            List<Map.Entry<String, Integer>> mods = new ArrayList<>(shortage.needsByMod.entrySet());
            mods.sort(
                    (a, b) -> {
                        int byCount = Integer.compare(b.getValue(), a.getValue());
                        return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                    });
            for (Map.Entry<String, Integer> mod : mods) {
                message.append("\n  ").append(mod.getKey()).append(": ").append(mod.getValue());
            }
        }
        throw Grug.fatal(message.toString());
    }

    /** "block" or "item". */
    public String kind() {
        return kind;
    }

    /** How many ids of the kind the mods need together. */
    public int needed() {
        return needed;
    }

    /** How many ids of the kind the game has free. */
    public int free() {
        return free;
    }

    /** How many more ids the mods need than there are. */
    public int missing() {
        return needed - free;
    }

    /** How many ids of the kind a mod needs, by the name of its directory. */
    public int needs(String mod) {
        return needsByMod.getOrDefault(mod, 0);
    }
}
