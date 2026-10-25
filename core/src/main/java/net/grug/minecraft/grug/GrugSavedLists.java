package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * The named lists a grug block entity keeps in its saved data, which is how a machine saves a
 * number of things that it can only count at runtime: the items a pipe is carrying, the contents of
 * a tank, the queue of a conveyor.
 *
 * <p>A list is a name, an ordered sequence of records, and each record is a map of named numbers
 * plus an optional item. That is the same shape as the reference's NBT list of compounds, one
 * compound per item with named fields and a nested stack, and it is the shape Forge and Fabric
 * expose as {@code ListTag} and {@code NbtList}. A block entity that only needs one number per name
 * keeps using {@link GrugSavedNumbers} instead.
 *
 * <p>Why a list of records rather than one number per name: a pipe's travelling items are a
 * sequence whose length changes, and a name such as {@code traveling0x} would need a name built per
 * index, which grug cannot do because {@code +} does not join strings. Why an item rather than a
 * number: the reference saves the whole stack in the record, and an item's identity cannot be
 * written as one of a record's numbers; the item's count and damage are ordinary numbers the script
 * names itself. A record with no item answers an empty {@link GrugOption} to the script, the way
 * {@link GrugSlotAccess} answers an empty slot, so a record whose item no longer exists is dropped
 * by the script rather than reported.
 *
 * <p>Keyed by the block entity itself, the way {@link GrugSavedNumbers} is, and held weakly, so a
 * block entity that is broken and collected takes its lists with it. Every method synchronises,
 * because on 1.20.6 the chunk meshing threads read them from a render pass while the main thread
 * writes them from an update packet.
 *
 * <p>Only a {@link Holder} has lists, because only a grug block entity writes them into its NBT: a
 * list on a vanilla chest would be gone the moment its chunk was, and a value that silently failed
 * to persist is worse than an error saying it cannot.
 */
public final class GrugSavedLists {

    /** One record of a list: named numbers, and the item the record holds, if any. */
    public static final class Record {
        private final Map<String, Double> numbers;
        private Object item;

        Record() {
            this.numbers = new HashMap<>();
            this.item = null;
        }

        /** A copy of a record a loader read back, or one a caller is about to store. */
        public Record(Map<String, Double> numbers, Object item) {
            this.numbers = new HashMap<>(numbers);
            this.item = item;
        }

        /** The numbers this record holds, for a loader to write. */
        public Map<String, Double> numbers() {
            return Collections.unmodifiableMap(numbers);
        }

        /** The adapter item this record holds, or null when it holds none. */
        public Object item() {
            return item;
        }
    }

    private static final Map<Object, Map<String, List<Record>>> LISTS = new WeakHashMap<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugSavedLists() {}

    /**
     * A block entity that saves its lists, which every loader's grug block entity is. An interface
     * rather than a registry of callbacks, for the same reason as {@link GrugSavedNumbers.Holder}.
     */
    public interface Holder {
        /** Marks the block entity changed, so the game saves its chunk and the lists with it. */
        void markSavedNumbersChanged();
    }

    /** Whether the block entity saves lists, which only a grug block entity does. */
    public static boolean isHolder(Object blockEntity) {
        return blockEntity instanceof Holder;
    }

    /** How many records the list holds, or 0 when the block entity has no such list. */
    public static synchronized int size(Object blockEntity, String name) {
        List<Record> records = list(blockEntity, name);
        return records != null ? records.size() : 0;
    }

    /**
     * Adds an empty record at the end of the list and answers its index, creating the list when the
     * block entity has none under that name. The caller has checked {@link #isHolder}.
     */
    public static synchronized int append(Object blockEntity, String name) {
        Map<String, List<Record>> lists =
                LISTS.computeIfAbsent(blockEntity, unused -> new HashMap<>());
        List<Record> records = lists.computeIfAbsent(name, unused -> new ArrayList<>());
        records.add(new Record());
        ((Holder) blockEntity).markSavedNumbersChanged();
        return records.size() - 1;
    }

    /**
     * Drops the record at {@code index}, moving every later record one place down. The caller has
     * checked that the record exists.
     */
    public static synchronized void remove(Object blockEntity, String name, int index) {
        list(blockEntity, name).remove(index);
        ((Holder) blockEntity).markSavedNumbersChanged();
    }

    /**
     * The record at {@code index}, or null when the block entity has no such list or no record at
     * that index.
     */
    public static synchronized Record record(Object blockEntity, String name, int index) {
        List<Record> records = list(blockEntity, name);
        if (records == null || index < 0 || index >= records.size()) {
            return null;
        }
        return records.get(index);
    }

    /**
     * Saves {@code value} under {@code key} in {@code record}, which the caller read with {@link
     * #record} or added with {@link #append} and so knows exists.
     */
    public static synchronized void setNumber(
            Object blockEntity, Record record, String key, double value) {
        record.numbers.put(key, value);
        ((Holder) blockEntity).markSavedNumbersChanged();
    }

    /**
     * Puts {@code item} in {@code record}, replacing the item that was there. The caller read the
     * record with {@link #record} or added it with {@link #append} and so knows it exists.
     */
    public static synchronized void setItem(Object blockEntity, Record record, Object item) {
        record.item = item;
        ((Holder) blockEntity).markSavedNumbersChanged();
    }

    /**
     * A copy of every list the block entity has saved, sorted by name and with each record copied,
     * so the NBT a loader writes from it is the same from one save to the next and a loader cannot
     * change what is stored by changing the copy.
     */
    public static synchronized Map<String, List<Record>> snapshot(Object blockEntity) {
        Map<String, List<Record>> lists = LISTS.get(blockEntity);
        if (lists == null) {
            return Collections.emptyMap();
        }
        Map<String, List<Record>> copy = new TreeMap<>();
        for (Map.Entry<String, List<Record>> entry : lists.entrySet()) {
            List<Record> records = new ArrayList<>(entry.getValue().size());
            for (Record record : entry.getValue()) {
                records.add(new Record(record.numbers, record.item));
            }
            copy.put(entry.getKey(), records);
        }
        return copy;
    }

    /**
     * Replaces every list the block entity has with {@code lists}, which is what loading its NBT
     * means: a name the saved data does not have is not kept from before the load.
     */
    public static synchronized void replace(Object blockEntity, Map<String, List<Record>> lists) {
        Map<String, List<Record>> copy = new HashMap<>();
        for (Map.Entry<String, List<Record>> entry : lists.entrySet()) {
            List<Record> records = new ArrayList<>(entry.getValue().size());
            for (Record record : entry.getValue()) {
                records.add(new Record(record.numbers, record.item));
            }
            copy.put(entry.getKey(), records);
        }
        LISTS.put(blockEntity, copy);
    }

    private static List<Record> list(Object blockEntity, String name) {
        Map<String, List<Record>> lists = LISTS.get(blockEntity);
        return lists != null ? lists.get(name) : null;
    }
}
