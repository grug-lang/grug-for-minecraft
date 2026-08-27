package net.grug.minecraft.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipeManager;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.List;

public class GrugScreenHandler extends ScreenHandler {
    private final Inventory blockInventory;
    private final List<GrugCraftingInventory> craftingMatrices = new ArrayList<>();
    private final List<CraftingResultInventory> craftingResults = new ArrayList<>();

    public GrugScreenHandler(PlayerEntity player, Inventory blockInventory, GrugGuiBuilder layout) {
        this.blockInventory = blockInventory;

        // Standard Slots
        for (GrugGuiBuilder.SlotDef def : layout.blockSlots) {
            this.addSlot(new Slot(blockInventory, def.index(), def.x(), def.y()));
        }

        // Crafting Grids
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            GrugCraftingInventory matrix = new GrugCraftingInventory(this, blockInventory, grid.startSlot());
            this.craftingMatrices.add(matrix);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    this.addSlot(new Slot(matrix, col + row * 3, grid.x() + col * 18, grid.y() + row * 18));
                }
            }
        }

        // Crafting Results (Phantom Inventories)
        for (int i = 0; i < layout.craftingResults.size(); i++) {
            GrugGuiBuilder.CraftingResultDef res = layout.craftingResults.get(i);
            CraftingResultInventory resultInv = new CraftingResultInventory();
            this.craftingResults.add(resultInv);

            // Map the result slot to the corresponding crafting matrix
            GrugCraftingInventory matrix = this.craftingMatrices.isEmpty() ? null
                    : this.craftingMatrices.get(Math.min(i, this.craftingMatrices.size() - 1));

            // CraftingResultSlot naturally prevents insertion and consumes the matrix
            // ingredients on click!
            this.addSlot(new CraftingResultSlot(player, matrix, resultInv, 0, res.x(), res.y()));
        }

        // Player Inventory
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

        updateCraftingResult();
    }

    @Override
    public void onSlotUpdate(Inventory inventory) {
        super.onSlotUpdate(inventory);
        updateCraftingResult();
    }

    private void updateCraftingResult() {
        for (int i = 0; i < craftingResults.size(); i++) {
            if (i < craftingMatrices.size()) {
                ItemStack result = CraftingRecipeManager.getInstance().craft(craftingMatrices.get(i));
                craftingResults.get(i).setStack(0, result);
            }
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return this.blockInventory.canPlayerUse(player);
    }
}
