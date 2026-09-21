package net.grug.minecraft.ornithe.inventory;

import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/** A 3x3 view onto nine consecutive slots of another inventory, for recipe matching. */
public class DummyCraftingInventory extends CraftingInventory {
    private final Inventory parent;
    private final int startSlot;

    public DummyCraftingInventory(Inventory parent, int startSlot) {
        super(null, 3, 3);
        this.parent = parent;
        this.startSlot = startSlot;
    }

    @Override
    public int getSize() {
        return 9;
    }

    @Override
    public ItemStack getItem(int slot) {
        return parent.getItem(startSlot + slot);
    }

    @Override
    public ItemStack getItem(int x, int y) {
        if (x >= 0 && x < 3 && y >= 0 && y < 3) {
            return this.getItem(x + y * 3);
        }
        return null;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return parent.removeItem(startSlot + slot, amount);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        parent.setItem(startSlot + slot, stack);
    }
}
