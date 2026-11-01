package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

/**
 * Asks a block entity's {@code extract_item} and {@code add_item} exports what a pipe may take from
 * it and put into it, and moves the items itself, as BuildCraft 2.2.14 does with {@code
 * ISpecialInventory}: the block entity decides per side and per item, the host performs the move.
 *
 * <p>Every method returns {@link #NO_EXPORT} when the block entity does not define the export, so a
 * caller can fall back to the slot ranges the block entity declared.
 *
 * <p>An export that returns false did not run, either because the script has no such export or
 * because it hit an err, which was reported already. Both leave the block entity unasked.
 */
final class GrugSpecialInventory {
    static final double NO_EXPORT = -1;

    private GrugSpecialInventory() {}

    /** How many items a pipe on {@code fromSide} would get, without taking any. */
    static double askExtract(Object blockEntity, double fromSide) {
        Result offered = Result.of(blockEntity);
        ExtractResponse response = callExtract(blockEntity, fromSide, false);
        if (response == null) {
            return NO_EXPORT;
        }
        return available(blockEntity, response, offered);
    }

    /**
     * Takes what the block entity gives a pipe on {@code fromSide} and puts it in the destination's
     * slot, which has to be empty. Returns how many items moved.
     */
    static double extractInto(
            Object blockEntity, Object destination, double destinationSlot, double fromSide) {
        ModLoaderAdapter adapter = GrugCore.getAdapter();
        if (adapter.getItemCountInSlot(destination, destinationSlot) != 0) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "BlockEntity.extract_item_into: slot "
                            + (int) destinationSlot
                            + " of the destination is not empty.");
            return 0;
        }
        Result offered = Result.of(blockEntity);
        ExtractResponse response = callExtract(blockEntity, fromSide, true);
        if (response == null) {
            return NO_EXPORT;
        }
        double count = available(blockEntity, response, offered);
        if (count == 0) {
            return 0;
        }

        Object item;
        double damage;
        double taken;
        if (response.kind() == ExtractResponse.Kind.CRAFTING_RESULT_CONSUMED) {
            // The export already used up the ingredients, and may have crafted the next result into
            // the holder, so the pipe gets the result as it was when the pipe asked and the host
            // takes nothing and runs no output_taken.
            item = offered.item;
            damage = offered.damage;
            taken = offered.count;
        } else if (response.kind() == ExtractResponse.Kind.CRAFTING_RESULT) {
            Object stack = adapter.getResultStack(blockEntity);
            item = adapter.getStackItem(stack);
            damage = adapter.getStackDamage(stack);
            taken = adapter.takeCraftingResult(blockEntity, count);
        } else {
            item = adapter.getItemInSlot(blockEntity, response.slot());
            damage = adapter.getItemDamageInSlot(blockEntity, response.slot());
            taken = adapter.takeItemFromSlot(blockEntity, response.slot(), count);
        }
        adapter.setItemInSlot(destination, destinationSlot, item, taken);
        adapter.setItemDamageInSlot(destination, destinationSlot, damage);
        return taken;
    }

    /**
     * Puts an item into the block entity as it answers, one slot at a time until the stack is in or
     * the block entity refuses, and returns how many went in. With {@code add} false it asks once
     * and changes nothing.
     *
     * <p>The item is the object its id resolves to, which the caller hands in rather than this
     * method dereferencing again: a block entity's member can hold an item that was collected since
     * (#93), and resolving it here put that dereference outside the id guard every host function
     * resolves through. See #895.
     */
    static double addItem(
            Object blockEntity,
            Object item,
            long itemId,
            double damage,
            double count,
            double fromSide,
            boolean add) {
        ModLoaderAdapter adapter = GrugCore.getAdapter();
        double remaining = count;
        double total = 0;
        while (remaining > 0) {
            AddResponse response = callAdd(blockEntity, itemId, damage, remaining, fromSide, add);
            if (response == null) {
                // Only the first ask can find no export; a later one found it already, so it ended
                // in an err.
                return remaining == count ? NO_EXPORT : total;
            }
            if (response.refused() || !validSlot(blockEntity, response.slot(), "add_item")) {
                break;
            }
            double moved =
                    adapter.insertItemIntoSlot(
                            blockEntity, response.slot(), item, damage, remaining, add);
            total += moved;
            remaining -= moved;
            if (!add || moved == 0) {
                break;
            }
        }
        return total;
    }

    /**
     * The crafting result a block entity held when it was asked, copied out because the export may
     * change the holder: a block entity that consumes the ingredients itself crafts the next result
     * before it answers.
     */
    private static final class Result {
        final Object item;
        final double damage;
        final double count;

        private Result(Object item, double damage, double count) {
            this.item = item;
            this.damage = damage;
            this.count = count;
        }

        static Result of(Object blockEntity) {
            ModLoaderAdapter adapter = GrugCore.getAdapter();
            Object stack = adapter.getResultStack(blockEntity);
            if (stack == null) {
                return new Result(null, 0, 0);
            }
            return new Result(
                    adapter.getStackItem(stack),
                    adapter.getStackDamage(stack),
                    adapter.getStackCount(stack));
        }
    }

    private static double available(Object blockEntity, ExtractResponse response, Result offered) {
        ModLoaderAdapter adapter = GrugCore.getAdapter();
        switch (response.kind()) {
            case SLOT:
                if (!validSlot(blockEntity, response.slot(), "extract_item")) {
                    return 0;
                }
                return Math.min(
                        response.amount(),
                        adapter.getItemCountInSlot(blockEntity, response.slot()));
            case CRAFTING_RESULT:
                Object stack = adapter.getResultStack(blockEntity);
                return stack == null ? 0 : adapter.getStackCount(stack);
            case CRAFTING_RESULT_CONSUMED:
                return offered.count;
            default:
                return 0;
        }
    }

    private static boolean validSlot(Object blockEntity, int slot, String export) {
        double size = GrugCore.getAdapter().getInventorySize(blockEntity);
        if (slot < 0 || slot >= size) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    export
                            + " named slot "
                            + slot
                            + ", but the inventory has slots 0 to "
                            + ((int) size - 1)
                            + ".");
            return false;
        }
        return true;
    }

    /** The response the export gave, or null when the block entity did not run the export. */
    private static ExtractResponse callExtract(
            Object blockEntity, double fromSide, boolean remove) {
        long handle = GrugCore.getAdapter().getGrugEntityHandle(blockEntity);
        if (handle == 0) {
            return null;
        }
        ExtractResponse response = new ExtractResponse();
        long responseId = GrugEntities.addEntity(GrugEntityType.ExtractResponse, response);
        if (!ExportFns.BlockEntity_extract_item(handle, fromSide, remove, responseId)) {
            response.close();
            return null;
        }
        return response.finish() ? response : refusedResponse();
    }

    private static AddResponse callAdd(
            Object blockEntity,
            long itemId,
            double damage,
            double count,
            double fromSide,
            boolean add) {
        long handle = GrugCore.getAdapter().getGrugEntityHandle(blockEntity);
        if (handle == 0) {
            return null;
        }
        AddResponse response = new AddResponse();
        long responseId = GrugEntities.addEntity(GrugEntityType.AddResponse, response);
        if (!ExportFns.BlockEntity_add_item(
                handle, itemId, damage, count, fromSide, add, responseId)) {
            response.close();
            return null;
        }
        return response.finish() ? response : refused();
    }

    /** What an export that returned without answering counts as, after the err was reported. */
    private static ExtractResponse refusedResponse() {
        ExtractResponse response = new ExtractResponse();
        response.refuse();
        return response;
    }

    private static AddResponse refused() {
        AddResponse response = new AddResponse();
        response.refuse();
        return response;
    }
}
