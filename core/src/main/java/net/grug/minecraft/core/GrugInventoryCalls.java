package net.grug.minecraft.core;

import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.Vec3;

/**
 * The two inventory host functions that change a block entity's contents, in one place, so the
 * process that runs the script and the process that holds the block entity change it the same way
 * (#1759).
 *
 * <p>{@code drop_inventory} and {@code BlockEntity.set_item_in_slot} write where the call runs. In
 * singleplayer, and on a dedicated server's own test, that process holds the block entity and its
 * adapter writes there. A client joined to a server that drives a run is the case that differs: the
 * overlay's {@code click()} is the caller, the block entity the test reads is the server's copy,
 * and a write to the client's mirror would be overwritten by the next block entity packet. The
 * client therefore names the block entity and the change, and the server applies it to its own
 * copy, the way {@link GrugGiveCalls} does for a give (#882).
 *
 * <p>Which side is authoritative is decided per call. Both calls only change a block entity near
 * the player who asked: a drop or a slot write on a block across the world is a write to somebody
 * else's block, and the server refuses it with a line the player is shown, the way it refuses a
 * give from a player who is not an operator. Unlike a give, the check is not about permission but
 * about whose block it is, so a client that placed a block only in its own copy cannot change the
 * server's world with it. A block entity the position holds but that has no inventory, such as a
 * sign, and a slot outside the inventory it does have, are refused the same way rather than left to
 * the cast or the array access that would throw on the server thread.
 */
public final class GrugInventoryCalls {
    /**
     * How far from the player a block entity a joined client may change is. Vanilla's own block
     * reach is 4.5 blocks on 1.20.6 and 5 on the older games; this is deliberately more generous,
     * so a block a test's overlay reaches is never refused for being a little farther than the
     * clicking player's arm, while a block across the room or the world still is.
     */
    static final double REACH = 8.0;

    @net.grug.minecraft.grug.GrugGenerated("utility class: never instantiated")
    private GrugInventoryCalls() {}

    /**
     * Drops the contents of the block entity at the position into the level, which is the test's
     * own edit of the world it runs in. A client that has started a run names the position to the
     * server instead, so the drop lands in the copy of the world the test reads.
     */
    public static void dropInventory(
            ModLoaderAdapter adapter, Object level, double x, double y, double z) {
        if (GrugControlClient.runActiveHere()) {
            GrugControlClient.dropInventoryOnServer(x, y, z);
            return;
        }
        adapter.dropInventory(level, x, y, z);
    }

    /**
     * Writes {@code count} of the item into {@code slot} of the block entity.
     *
     * <p>{@code method} is the host function's name, which the report of an item the client cannot
     * name has to carry: it is what tells a mod which of its calls did not write.
     */
    public static void setItemInSlot(
            ModLoaderAdapter adapter,
            Object blockEntity,
            double slot,
            Object item,
            double count,
            String method) {
        if (GrugControlClient.runActiveHere()) {
            String name = adapter.itemName(item);
            if (name == null || name.isEmpty()) {
                // A joined client can only name an item its own loader can name, and a write it
                // cannot name is reported rather than made to a copy the server would overwrite.
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr,
                        method
                                + ": This loader cannot name the item, so a client joined to a"
                                + " server cannot ask the server that holds the block entity to"
                                + " write it.");
                return;
            }
            BlockPos position = adapter.getBlockPosOfBlockEntity(blockEntity);
            // A local write truncates both numbers to whole ones (every adapter's own write takes
            // an int), so the forwarded write names the same whole slot and count: a fraction
            // would otherwise be a message the wire refuses, and the two paths would disagree
            // about what the call means.
            GrugControlClient.setItemInSlotOnServer(
                    position.x(), position.y(), position.z(), (int) slot, name, (int) count);
            return;
        }
        adapter.setItemInSlot(blockEntity, slot, item, count);
    }

    /**
     * Applies a drop the joined client asked for, on the server's own copy of the block entity
     * (#1759). Called on the server thread, by the drain that follows the forwarded call the drop
     * was part of.
     *
     * <p>Returns null when the contents were dropped, and otherwise the one-line error to show the
     * player who asked, so a refusal is never a silent no-op. The block is looked up in this
     * server's own level, by the position the client sent, because the client's block entity object
     * lives in the other process.
     *
     * <p>The drop moves the world revision here, explicitly, for the reason {@link
     * GrugGiveCalls#giveOnServer} does: this drain runs between two runner ticks, so without the
     * bump a settle answer the server remembered for the old revision would outlive the drop.
     */
    public static String dropInventoryOnServer(
            ModLoaderAdapter adapter, Object player, double x, double y, double z) {
        String refusal = checkOwnership(adapter, player, x, y, z);
        if (refusal != null) return refusal;
        Object level = adapter.getLevel();
        Object blockEntity = adapter.getBlockEntity(level, x, y, z);
        if (blockEntity == null) {
            return "The server has no block entity at that position to drop the contents of.";
        }
        if (inventorySize(adapter, blockEntity) < 0) {
            return "The block entity at that position has no inventory to drop the contents of.";
        }
        GrugWorldRevision.bump();
        adapter.dropInventory(level, x, y, z);
        return null;
    }

    /**
     * Applies a slot write the joined client asked for, on the server's own copy of the block
     * entity (#1759), and answers the same refusal {@link #dropInventoryOnServer} does.
     *
     * <p>The item is found by the name the client sent, not by an index, so a client and a server
     * whose item lists differ still write the same item, as a give does. A count of 0 clears the
     * slot, which is what the host function's own local path does.
     */
    public static String setItemInSlotOnServer(
            ModLoaderAdapter adapter,
            Object player,
            double x,
            double y,
            double z,
            double slot,
            String itemName,
            int count) {
        String refusal = checkOwnership(adapter, player, x, y, z);
        if (refusal != null) return refusal;
        Object blockEntity = adapter.getBlockEntity(adapter.getLevel(), x, y, z);
        if (blockEntity == null) {
            return "The server has no block entity at that position to write into.";
        }
        double size = inventorySize(adapter, blockEntity);
        if (size < 0) {
            return "The block entity at that position has no inventory to write into.";
        }
        // The slot the adapter writes is the whole part of the number, the way every adapter's own
        // write casts it, so the range is checked against that same slot.
        int slotIndex = (int) slot;
        if (slotIndex < 0 || slotIndex >= size) {
            // The write goes to a slot the inventory has to have, checked here rather than left to
            // the adapter's own array access: the client may name any slot up to the wire's own
            // cap, and an index the inventory does not have would throw on the server thread.
            return "The block entity's inventory has no slot " + slotIndex + ".";
        }
        Object item = GrugGiveCalls.itemNamed(adapter, itemName);
        if (item == null) {
            return "The server has no item named " + itemName + ".";
        }
        GrugWorldRevision.bump();
        adapter.setItemInSlot(blockEntity, slotIndex, item, count);
        return null;
    }

    /**
     * How many slots the block entity's inventory has, or -1 when it has none. Every loader's own
     * inventory access casts the block entity to its game's container type, so a block entity that
     * is not one answers with that cast's exception rather than a size: a chest and a sign are both
     * block entities, and the sign has no inventory a drop or a write could change.
     */
    static double inventorySize(ModLoaderAdapter adapter, Object blockEntity) {
        try {
            return adapter.getInventorySize(blockEntity);
        } catch (ClassCastException e) {
            return -1;
        }
    }

    /**
     * The refusal for a change to a block entity that is not the asking player's, or null when it
     * is. A player's own block is one within {@link #REACH} of them, which is the most the game's
     * own reach lets them touch, so a client cannot change a block it only imagines it placed.
     */
    static String checkOwnership(
            ModLoaderAdapter adapter, Object player, double x, double y, double z) {
        if (player == null) {
            return "No player has joined yet, so there is nobody to change a block entity for.";
        }
        Vec3 position = adapter.playerPosition(player);
        if (position == null) {
            return "The server cannot tell where you are, so it will not change a block entity"
                    + " for you.";
        }
        if (!withinReach(position, x, y, z)) {
            return "You can only change a block entity within "
                    + (int) REACH
                    + " blocks of yourself.";
        }
        return null;
    }

    /**
     * Whether the block at the position is within {@link #REACH} of the player, measured to the
     * block's centre, which is the point the game's own reach also uses. The block is floored the
     * way every loader's adapter floors it, so a position on a block's edge is that block.
     */
    static boolean withinReach(Vec3 player, double x, double y, double z) {
        double dx = (Math.floor(x) + 0.5) - player.x();
        double dy = (Math.floor(y) + 0.5) - player.y();
        double dz = (Math.floor(z) + 0.5) - player.z();
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }
}
