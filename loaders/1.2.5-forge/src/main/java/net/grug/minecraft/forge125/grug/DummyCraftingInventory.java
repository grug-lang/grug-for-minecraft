package net.grug.minecraft.forge125.grug;

import net.minecraft.src.IInventory;
import net.minecraft.src.InventoryCrafting;
import net.minecraft.src.ItemStack;

/**
 * Presents nine slots of an existing inventory as a 3x3 crafting matrix.
 *
 * <p>1.2.5 recipes are matched against an {@code InventoryCrafting}, which cannot be pointed at
 * another inventory directly, so this subclass forwards every access to the block inventory that
 * owns the crafting grid. The container passed to {@code super} is null because each mutating
 * method is overridden and never reaches the parent's {@code onCraftMatrixChanged} callback.
 */
public class DummyCraftingInventory extends InventoryCrafting {
    private final IInventory parent;
    private final int startSlot;

    public DummyCraftingInventory(IInventory parent, int startSlot) {
        super(null, 3, 3);
        this.parent = parent;
        this.startSlot = startSlot;
    }

    @Override
    public int getSizeInventory() {
        return 9;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return parent.getStackInSlot(startSlot + slot);
    }

    @Override
    public ItemStack getStackInRowAndColumn(int x, int y) {
        if (x >= 0 && x < 3 && y >= 0 && y < 3) {
            return getStackInSlot(x + y * 3);
        }
        return null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        return parent.decrStackSize(startSlot + slot, amount);
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        parent.setInventorySlotContents(startSlot + slot, stack);
    }
}
