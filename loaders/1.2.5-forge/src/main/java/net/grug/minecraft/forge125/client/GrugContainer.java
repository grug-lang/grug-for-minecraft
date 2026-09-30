package net.grug.minecraft.forge125.client;

import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.src.Container;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.IInventory;
import net.minecraft.src.ItemStack;
import net.minecraft.src.Slot;

/**
 * The slot layout behind {@link GrugScreen}: a {@code Container} built from a grug GUI definition.
 *
 * <p>1.2.5 predates menu providers, so the slots are assembled by hand and the container is handed
 * straight to the screen instead of being registered with a menu type.
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
            addSlot(
                    new Slot(blockInventory, result.slot(), result.x(), result.y()) {
                        @Override
                        public boolean isItemValid(ItemStack stack) {
                            return false;
                        }

                        @Override
                        public void onPickupFromSlot(ItemStack stack) {
                            super.onPickupFromSlot(stack);
                            if (blockInventory instanceof GrugBlockEntity) {
                                ((GrugBlockEntity) blockInventory)
                                        .notifyOutputTaken(
                                                result.slot(), stack != null ? stack.stackSize : 0);
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

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return blockInventory.isUseableByPlayer(player);
    }
}
