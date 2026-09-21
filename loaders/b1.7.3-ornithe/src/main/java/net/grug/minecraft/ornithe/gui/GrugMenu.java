package net.grug.minecraft.ornithe.gui;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.menu.InventoryMenu;
import net.minecraft.inventory.slot.InventorySlot;
import net.minecraft.item.ItemStack;

/** Slot layout for a grug GUI, built from the `GrugGuiBuilder` a mod filled in. */
public class GrugMenu extends InventoryMenu {
    private final Inventory blockInventory;
    private final int customSlotCount;
    private final boolean hasPlayerSlots;

    public GrugMenu(PlayerEntity player, Inventory blockInventory, GrugGuiBuilder layout) {
        this.blockInventory = blockInventory;

        for (GrugGuiBuilder.SlotDef def : layout.blockSlots) {
            this.addSlot(new InventorySlot(blockInventory, def.index(), def.x(), def.y()));
        }

        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    this.addSlot(new InventorySlot(blockInventory, grid.startSlot() + col + row * 3,
                            grid.x() + col * 18, grid.y() + row * 18));
                }
            }
        }

        for (GrugGuiBuilder.CraftingResultDef res : layout.craftingResults) {
            this.addSlot(new InventorySlot(blockInventory, res.slot(), res.x(), res.y()) {
                @Override
                public boolean isItemAllowed(ItemStack stack) {
                    return false;
                }

                @Override
                public void onItemRemoved(ItemStack stack) {
                    super.onItemRemoved(stack);
                    if (blockInventory instanceof GrugBlockEntity gbe) {
                        gbe.notifyOutputTaken(res.slot(), stack != null ? stack.size : 0);
                    }
                }
            });
        }

        this.customSlotCount = this.slots.size();
        this.hasPlayerSlots = layout.hasPlayerInventory;

        if (layout.hasPlayerInventory) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new InventorySlot(player.inventory, col + row * 9 + 9,
                            layout.playerInvX + col * 18, layout.playerInvY + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new InventorySlot(player.inventory, col, layout.hotbarX + col * 18, layout.hotbarY));
            }
        }
    }

    @Override
    public boolean isValid(PlayerEntity player) {
        return this.blockInventory.isValid(player);
    }

    @Override
    public ItemStack quickMoveItem(int index) {
        if (!this.hasPlayerSlots) {
            return null;
        }

        ItemStack original = null;
        InventorySlot slot = this.getSlot(index);

        if (slot != null && slot.hasItem()) {
            ItemStack inSlot = slot.getItem();
            original = inSlot.copy();

            // The player's 27 main slots come first, then the 9 hotbar slots
            int start = this.customSlotCount;
            if (index >= start) {
                if (index < start + 27) {
                    this.moveItem(inSlot, start + 27, start + 36, false);
                } else {
                    this.moveItem(inSlot, start, start + 27, false);
                }
            } else {
                this.moveItem(inSlot, start, start + 36, true);
            }

            if (inSlot.size == 0) {
                slot.setItem(null);
            } else {
                slot.markDirty();
            }

            // Nothing was moved
            if (inSlot.size == original.size) {
                return null;
            }

            slot.onItemRemoved(inSlot);
        }

        return original;
    }
}
