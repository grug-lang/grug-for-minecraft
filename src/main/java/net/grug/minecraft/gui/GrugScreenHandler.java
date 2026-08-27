package net.grug.minecraft.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

public class GrugScreenHandler extends ScreenHandler {
    private final Inventory blockInventory;

    public GrugScreenHandler(PlayerEntity player, Inventory blockInventory, GrugGuiBuilder layout) {
        this.blockInventory = blockInventory;

        for (GrugGuiBuilder.SlotDef def : layout.blockSlots) {
            // Note: If def.isOutput() is true, you would typically use a custom Slot here
            // that prevents players from placing items into it.
            this.addSlot(new Slot(blockInventory, def.index(), def.x(), def.y()));
        }

        if (layout.hasPlayerInventory) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    this.addSlot(new Slot(player.inventory, col + row * 9 + 9, layout.playerInvX + col * 18,
                            layout.playerInvY + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(player.inventory, col, layout.hotbarX + col * 18, layout.hotbarY));
            }
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return this.blockInventory.canPlayerUse(player);
    }
}
