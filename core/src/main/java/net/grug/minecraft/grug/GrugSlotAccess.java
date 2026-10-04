package net.grug.minecraft.grug;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which slots of a block entity's inventory another block may take from and put into.
 *
 * <p>The rule is declared in grug, by the block entity's own script or by anything else holding the
 * block entity, and is keyed here by the block entity itself rather than stored on it, so one copy
 * serves every loader and a vanilla chest (which never declares anything) is accessible everywhere.
 *
 * <p>Nothing here is about any one kind of block that moves items around. The wooden pipe is the
 * first thing to ask, and a machine is the first thing to answer, but the rule is only about slots,
 * so a quarry, a sorter or a modded furnace can use the same four functions.
 *
 * <p>The slots are the inventory's own, plus one more: the slot after the last inventory slot is
 * the block entity's crafting result, which is not an inventory slot but is what a machine's output
 * is and so the slot worth granting. A block entity that never declared a rule grants every slot;
 * one that did grants the declared first-to-last range, which is empty when the last is below the
 * first.
 *
 * <p>Entries are held weakly, so a block entity that is broken and collected takes its rule with it
 * rather than pinning the game object for the rest of the session.
 */
public final class GrugSlotAccess {

    /**
     * The declared ranges per block entity. A block entity absent from the map declares nothing, so
     * every slot is accessible; that is the permissive default rather than a stored all-slot range,
     * so declaring nothing costs nothing and a rule can be cleared by declaring an empty range.
     */
    private static final Map<Object, Ranges> DECLARED = new WeakHashMap<>();

    private GrugSlotAccess() {}

    /** Declares the first and last slot, inclusive, another block may take from. */
    public static void declareExtractable(Object blockEntity, double first, double last) {
        ranges(blockEntity).extractable = new SlotRange(first, last);
    }

    /** Declares the first and last slot, inclusive, another block may put into. */
    public static void declareInsertable(Object blockEntity, double first, double last) {
        ranges(blockEntity).insertable = new SlotRange(first, last);
    }

    /**
     * Whether another block may take from {@code slot}, which may be the crafting result slot one
     * past the inventory's last. A block entity with no declared range is extractable everywhere.
     */
    public static boolean isExtractable(Object blockEntity, double slot) {
        SlotRange declared = rangeFor(blockEntity, true);
        return declared == null || declared.contains(truncate(slot));
    }

    /**
     * Whether another block may put into {@code slot}. A block entity with no declared range is
     * insertable everywhere, and insert_item_into_inventory never reaches the crafting result one
     * past the inventory's last, so a rule covers that slot only if a mod asks for it by name.
     */
    public static boolean isInsertable(Object blockEntity, double slot) {
        SlotRange declared = rangeFor(blockEntity, false);
        return declared == null || declared.contains(truncate(slot));
    }

    /**
     * A slot index, with the fraction dropped.
     *
     * <p>Every other function that takes a slot truncates it: get_item_in_slot reads the slot below
     * 0.5, so this has to answer about that same slot rather than about a range of half-pixels.
     * Left untruncated it would say yes for 0.5 and then let the caller read slot 0, which is a
     * silently wrong answer rather than an err.
     */
    private static double truncate(double slot) {
        return (int) slot;
    }

    /** The declared range for one direction, or null when that direction was never declared. */
    private static SlotRange rangeFor(Object blockEntity, boolean extractable) {
        Ranges ranges = DECLARED.get(blockEntity);
        if (ranges == null) return null;
        return extractable ? ranges.extractable : ranges.insertable;
    }

    private static Ranges ranges(Object blockEntity) {
        Ranges ranges = DECLARED.get(blockEntity);
        if (ranges == null) {
            ranges = new Ranges();
            DECLARED.put(blockEntity, ranges);
        }
        return ranges;
    }

    /** The two directions of one block entity's rule, either of which may be undeclared. */
    private static final class Ranges {
        private SlotRange extractable = null;
        private SlotRange insertable = null;
    }

    /**
     * An inclusive slot range. The crafting result slot, one past the end of the inventory, is the
     * only slot outside the inventory a rule can name, so nothing here has to know whether a block
     * entity has one: the caller asks with the slot it means and this answers whether the rule
     * covers it.
     */
    private static final class SlotRange {
        private final double first;
        private final double last;

        SlotRange(double first, double last) {
            this.first = first;
            this.last = last;
        }

        boolean contains(double slot) {
            // A last below the first is an empty range rather than a reversed one, so declaring
            // no slot at all is expressed the same way as any other declaration.
            return first <= last && slot >= first && slot <= last;
        }
    }
}
