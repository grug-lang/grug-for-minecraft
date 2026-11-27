package net.grug.minecraft.grug;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * The named text a grug block entity keeps in its saved data, which is how a machine's text
 * outlives its chunk being unloaded and loaded again: a tank's liquid name, a pipe's per-side
 * identity, a template's text.
 *
 * <p>A grug entity's member variables live in the script and start over whenever the entity is
 * created again, which a chunk reload does. These do not: each loader's grug block entity writes
 * them into its NBT when the game saves it and reads them back when the game loads it, and on a
 * version whose client keeps its own copy of the block entity, its update packet carries them too.
 *
 * <p>Keyed by the block entity itself, the same way {@link GrugSavedNumbers} is, so one copy serves
 * every loader, and held weakly, so a block entity that is broken and collected takes its text with
 * it. Every method synchronises, because on 1.20.6 the chunk meshing threads read them from a
 * render pass while the main thread writes them from an update packet.
 *
 * <p>Only a {@link Holder} has text, because only a grug block entity writes it into its NBT: text
 * set on a vanilla chest would be gone the moment its chunk was, and a value that silently fails to
 * persist is worse than an error saying it cannot. A list's records keep their own text through
 * {@link GrugSavedLists}.
 */
public final class GrugSavedStrings {

    private static final Map<Object, Map<String, String>> STRINGS = new WeakHashMap<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugSavedStrings() {}

    /**
     * A block entity that saves its text, which every loader's grug block entity is. An interface
     * rather than a registry of callbacks, for the same reason as {@link GrugSavedNumbers.Holder}.
     */
    public interface Holder {
        /** Marks the block entity changed, so the game saves its chunk and the text with it. */
        void markSavedNumbersChanged();
    }

    /** Whether the block entity saves text, which only a grug block entity does. */
    public static boolean isHolder(Object blockEntity) {
        return blockEntity instanceof Holder;
    }

    /**
     * The text saved under {@code name}, or null when none has been: the same answer a fresh NBT
     * tag gives, so a machine that has never run reads the state it starts in, and an empty string
     * is told apart from no text at all.
     */
    public static synchronized String get(Object blockEntity, String name) {
        Map<String, String> strings = STRINGS.get(blockEntity);
        if (strings == null) {
            return null;
        }
        return strings.get(name);
    }

    /**
     * Saves {@code value} under {@code name}.
     *
     * <p>Nothing is marked when the text saved under that name is already {@code value}, because a
     * script may save the same text every tick, and a machine's tick is not a change to the saved
     * data. A name that was never written reads back as null, so saving null into it marks nothing,
     * and so does saving the null the map already holds.
     *
     * @return false when the block entity is not a {@link Holder}, which the caller reports;
     *     nothing is saved then
     */
    public static boolean set(Object blockEntity, String name, String value) {
        if (!isHolder(blockEntity)) {
            return false;
        }
        boolean changed;
        synchronized (GrugSavedStrings.class) {
            Map<String, String> strings =
                    STRINGS.computeIfAbsent(blockEntity, unused -> new HashMap<>());
            String stored = strings.get(name);
            changed = value == null ? stored != null : !value.equals(stored);
            strings.put(name, value);
        }
        // Outside this class's monitor, for the reason GrugSavedNumbers.set gives.
        if (changed) {
            ((Holder) blockEntity).markSavedNumbersChanged();
        }
        return true;
    }

    /**
     * A copy of every text the block entity has saved, sorted by name so the NBT a loader writes
     * from it is the same from one save to the next.
     */
    public static synchronized Map<String, String> snapshot(Object blockEntity) {
        Map<String, String> strings = STRINGS.get(blockEntity);
        if (strings == null) {
            return Collections.emptyMap();
        }
        return new TreeMap<>(strings);
    }

    /**
     * Replaces every text the block entity has with {@code strings}, which is what loading its NBT
     * means: a name the saved data does not have is not kept from before the load.
     */
    public static synchronized void replace(Object blockEntity, Map<String, String> strings) {
        STRINGS.put(blockEntity, new HashMap<>(strings));
    }
}
