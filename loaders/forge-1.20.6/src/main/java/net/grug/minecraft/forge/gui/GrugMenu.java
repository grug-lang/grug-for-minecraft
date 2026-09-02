package net.grug.minecraft.forge.gui;

import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class GrugMenu extends AbstractContainerMenu {
    private final Container blockContainer;

    public GrugMenu(MenuType<?> menuType, int containerId, Inventory playerInventory, Container blockContainer,
            GrugGuiBuilder layout) {
        super(menuType, containerId);
        this.blockContainer = blockContainer;

        // Standard Slots
        for (GrugGuiBuilder.SlotDef def : layout.blockSlots) {
            this.addSlot(new Slot(blockContainer, def.index(), def.x(), def.y()));
        }

        // Crafting Grids (Standard Slots)
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    this.addSlot(new Slot(blockContainer, grid.startSlot() + col + row * 3,
                            grid.x() + col * 18, grid.y() + row * 18));
                }
            }
        }

        // Crafting Results (Real Slots)
        for (GrugGuiBuilder.CraftingResultDef res : layout.craftingResults) {
            this.addSlot(new Slot(blockContainer, res.slot(), res.x(), res.y()) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false; // Prevent player from inserting manually
                }

                @Override
                public void onTake(Player player, ItemStack stack) {
                    super.onTake(player, stack);
                    if (blockContainer instanceof GrugBlockEntity gbe) {
                        gbe.notifyOutputTaken(res.slot(), stack.getCount());
                    }
                }
            });
        }

        // Player Inventory
        if (layout.hasPlayerInventory) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                            layout.playerInvX + col * 18, layout.playerInvY + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col,
                        layout.hotbarX + col * 18, layout.hotbarY));
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack originalStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            originalStack = stackInSlot.copy();

            int customSlotCount = this.slots.size() - 36;

            if (index >= customSlotCount) {
                // The shift-click originated from the player's own inventory
                if (index >= customSlotCount && index < customSlotCount + 27) {
                    // Main Inventory -> Hotbar
                    if (!this.moveItemStackTo(stackInSlot, customSlotCount + 27, customSlotCount + 36, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index >= customSlotCount + 27 && index < customSlotCount + 36) {
                    // Hotbar -> Main Inventory
                    if (!this.moveItemStackTo(stackInSlot, customSlotCount, customSlotCount + 27, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            } else {
                // The shift-click originated from the custom GUI
                if (!this.moveItemStackTo(stackInSlot, customSlotCount, customSlotCount + 36, true)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stackInSlot.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (stackInSlot.getCount() == originalStack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, stackInSlot);
        }

        return originalStack;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.blockContainer.stillValid(player);
    }
}
