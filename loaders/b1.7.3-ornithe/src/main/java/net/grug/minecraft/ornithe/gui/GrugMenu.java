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
}
