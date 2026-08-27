package net.grug.minecraft.grug;

import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

public class DummyCraftingInventory extends CraftingInventory {
    private final Inventory parent;
    private final int startSlot;

    public DummyCraftingInventory(Inventory parent, int startSlot) {
        super(null, 3, 3);
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
        if (x >= 0 && x < 3 && y >= 0 && y < 3) {
            return this.getStack(x + y * 3);
        }
        return null;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        return parent.removeStack(startSlot + slot, amount);
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        this.parent.setStack(startSlot + slot, stack);
    }
}
