package net.grug.minecraft.forge125.client;

import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.src.Container;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.IInventory;
import net.minecraft.src.InventoryBasic;
import net.minecraft.src.ItemStack;
import net.minecraft.src.Slot;

/**
 * The slot layout behind {@link GrugScreen}: a {@code Container} built from a grug GUI definition.
 *
 * <p>1.2.5 predates menu providers, so the slots are assembled by hand and the container is handed
 * straight to the screen instead of being registered with a menu type.
 *
 * <p>Both sides build one from the same layout: the server from the block entity's own inventory,
 * when {@code GUI.open} opens it for a player (#1098), and the client from the layout the server
 * sent, so the two number their slots identically. A client whose block entity has not arrived yet
 * builds it on a placeholder inventory instead, as vanilla menus do (#425).
 */
public class GrugContainer extends Container {
    private final IInventory blockInventory;

    public GrugContainer(EntityPlayer player, IInventory blockInventory, GrugGuiBuilder layout) {
        this.blockInventory = blockInventory;

        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    addSlot(
                            new Slot(
                                    blockInventory,
                                    grid.startSlot() + col + row * 3,
                                    grid.x() + col * 18,
                                    grid.y() + row * 18));
                }
            }
        }

        for (GrugGuiBuilder.CraftingResultDef result : layout.craftingResults) {
            // The result is not an inventory slot; the block entity owns a one-slot container for
            // it. Taking from that container is the same path take_crafting_result uses. A
            // placeholder has no block entity behind it, so its result slot is one of its own and
            // nothing is notified when it is taken from: the server's own take sends the item. A
            // joined client's copy does not notify either, because the hook is the block entity's
            // own work: it consumes the taken result and restocks the grid, which only the server
            // owns, and a client's unsynced neighbours would diverge with no packet to correct them
            // (#1098).
            IInventory resultInventory = resultInventoryOf(blockInventory);
            addSlot(
                    new Slot(resultInventory, 0, result.x(), result.y()) {
                        @Override
                        public boolean isItemValid(ItemStack stack) {
                            return false;
                        }

                        @Override
                        public void onPickupFromSlot(ItemStack stack) {
                            super.onPickupFromSlot(stack);
                            if (!player.worldObj.isRemote
                                    && blockInventory instanceof GrugBlockEntity) {
                                ((GrugBlockEntity) blockInventory)
                                        .notifyOutputTaken(stack != null ? stack.stackSize : 0);
                            }
                        }
                    });
        }

        if (layout.hasPlayerInventory) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    addSlot(
                            new Slot(
                                    player.inventory,
                                    col + row * 9 + 9,
                                    layout.playerInvX + col * 18,
                                    layout.playerInvY + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(player.inventory, col, layout.hotbarX + col * 18, layout.hotbarY));
            }
        }
    }

    /**
     * The container the result slot reads: the block entity's own one-slot container, or a new one
     * when there is no block entity behind the layout, which is the client's placeholder (#425).
     */
    private static IInventory resultInventoryOf(IInventory blockInventory) {
        if (blockInventory instanceof GrugBlockEntity) {
            return ((GrugBlockEntity) blockInventory).getResultInventory();
        }
        return new InventoryBasic("Crafting Result", 1);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return blockInventory.isUseableByPlayer(player);
    }
}
