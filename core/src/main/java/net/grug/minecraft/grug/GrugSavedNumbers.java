package net.grug.minecraft.grug;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * The named numbers a grug block entity keeps in its saved data, which is how a machine's state
 * outlives its chunk being unloaded and loaded again.
 *
 * <p>A grug entity's member variables live in the script and start over whenever the entity is
 * created again, which a chunk reload does. These do not: each loader's grug block entity writes
 * them into its NBT when the game saves it and reads them back when the game loads it, and on a
 * version whose client keeps its own copy of the block entity, its update packet carries them too.
 *
 * <p>Keyed by the block entity itself, the same way {@link GrugSlotAccess} is, so one copy serves
 * every loader. Only a {@link Holder} has numbers, because only a grug block entity saves them: a
 * number set on a vanilla chest would be gone the moment its chunk was, and a value that silently
 * fails to persist is worse than an error saying it cannot.
 *
 * <p>Entries are held weakly, so a block entity that is broken and collected takes its numbers with
 * it. Every method synchronises, because on 1.20.6 the chunk meshing threads read them from a
 * render pass while the main thread writes them from an update packet.
 */
public final class GrugSavedNumbers {

    private static final Map<Object, Map<String, Double>> NUMBERS = new WeakHashMap<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugSavedNumbers() {}

    /**
     * A block entity that saves its numbers, which every loader's grug block entity is.
     *
     * <p>An interface rather than a registry of callbacks, because a callback stored as a value of
     * the weak map would hold its block entity strongly and the entry would never be collected.
     */
    public interface Holder {
        /**
         * Marks the block entity changed, so the game saves its chunk. A chunk the game thinks is
         * unchanged is not written, and the number with it.
         */
        void markSavedNumbersChanged();
    }

    /** Whether the block entity saves numbers, which only a grug block entity does. */
    public static boolean isHolder(Object blockEntity) {
        return blockEntity instanceof Holder;
    }

    /**
     * The number saved under {@code name}, or 0 when none has been: the same default a fresh NBT
     * tag answers, so a machine that has never run reads the state it starts in.
     */
    public static synchronized double get(Object blockEntity, String name) {
        Map<String, Double> numbers = NUMBERS.get(blockEntity);
        if (numbers == null) {
            return 0.0;
        }
        Double value = numbers.get(name);
        return value != null ? value : 0.0;
    }

    /**
     * Saves {@code value} under {@code name}.
     *
     * @return false when the block entity is not a {@link Holder}, which the caller reports;
     *     nothing is saved then
     */
    public static synchronized boolean set(Object blockEntity, String name, double value) {
        if (!isHolder(blockEntity)) {
            return false;
        }
        Map<String, Double> numbers = NUMBERS.get(blockEntity);
        if (numbers == null) {
            numbers = new HashMap<>();
            NUMBERS.put(blockEntity, numbers);
        }
        numbers.put(name, value);
        ((Holder) blockEntity).markSavedNumbersChanged();
        return true;
    }

    /**
     * A copy of every number the block entity has saved, sorted by name so the NBT a loader writes
     * from it is the same from one save to the next.
     */
    public static synchronized Map<String, Double> snapshot(Object blockEntity) {
        Map<String, Double> numbers = NUMBERS.get(blockEntity);
        if (numbers == null) {
            return Collections.emptyMap();
        }
        return new TreeMap<>(numbers);
    }

    /**
     * Replaces every number the block entity has with {@code numbers}, which is what loading its
     * NBT means: a name the saved data does not have is not kept from before the load.
     */
    public static synchronized void replace(Object blockEntity, Map<String, Double> numbers) {
        NUMBERS.put(blockEntity, new HashMap<>(numbers));
    }
}
