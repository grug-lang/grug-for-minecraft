package net.grug.minecraft.stationapi.gui;

import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;

public class GrugCraftingInventory extends CraftingInventory {
    private final ScreenHandler handler;
    private final Inventory parent;
    private final int startSlot;

    public GrugCraftingInventory(ScreenHandler handler, Inventory parent, int startSlot) {
        super(handler, 3, 3);
        this.handler = handler;
        this.parent = parent;
        this.startSlot = startSlot;
    }

    @Override
    public int size() {
        return 9;
    }

    @Override
    public ItemStack getStack(int slot) {
        return parent.getStack(startSlot + slot);
    }

    @Override
    public ItemStack getStack(int x, int y) {
        if (x >= 0 && x < 3) {
            return this.getStack(x + y * 3);
        }
        return null;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack stack = parent.removeStack(startSlot + slot, amount);
        if (stack != null) {
            this.handler.onSlotUpdate(this);
        }
        return stack;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        this.parent.setStack(startSlot + slot, stack);
        this.handler.onSlotUpdate(this);
    }
}
