package net.grug.minecraft.ornithe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Method;

/**
 * The inventory writes that the Alpha a0.2.1 server's {@code Inventory} interface does not have.
 *
 * <p>The client's interface declares {@code setItem} and {@code removeItem}; the server's has only
 * {@code getSize} and {@code getItem}, and the writes live on each concrete inventory. A call
 * compiled against the client's interface throws {@code NoSuchMethodError} on the server, so there
 * the write goes to the object's own {@code setItem} by reflection. {@code removeItem} is reflected
 * the same way, because grug's own block entity implements it (with its {@code markDirty} and the
 * {@code item_extracted} export); only a vanilla server inventory, which has no such method at all
 * (the a0.2.1 chest and player inventory declare {@code getItem}, {@code setItem} and nothing that
 * removes), gets the removal built from {@code getItem} and {@code setItem}. See #1021.
 */
@GrugGenerated("the server's inventory, which only the dedicated-server job reaches")
final class GrugInventoryAccess {
    /** The intermediary name of {@code setItem} on the concrete server inventories. */
    private static final String SERVER_SET_ITEM = "m_33431716";

    /** The intermediary name of {@code removeItem}, which grug's block entity implements. */
    private static final String SERVER_REMOVE_ITEM = "m_07546956";

    private static final boolean CLIENT =
            FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

    private GrugInventoryAccess() {}

    static void setItem(Inventory inventory, int slot, ItemStack stack) {
        if (CLIENT) {
            inventory.setItem(slot, stack);
            return;
        }
        try {
            Method method =
                    inventory.getClass().getMethod(SERVER_SET_ITEM, int.class, ItemStack.class);
            method.setAccessible(true);
            method.invoke(inventory, slot, stack);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    inventory.getClass().getName() + " has no " + SERVER_SET_ITEM + "(int, stack).",
                    e);
        }
    }

    static ItemStack removeItem(Inventory inventory, int slot, int count) {
        if (CLIENT) {
            return inventory.removeItem(slot, count);
        }
        Method real = null;
        try {
            real = inventory.getClass().getMethod(SERVER_REMOVE_ITEM, int.class, int.class);
        } catch (NoSuchMethodException e) {
            // A vanilla server inventory: built below from getItem and setItem.
        }
        if (real != null) {
            try {
                real.setAccessible(true);
                return (ItemStack) real.invoke(inventory, slot, count);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        inventory.getClass().getName() + "." + SERVER_REMOVE_ITEM + " failed.", e);
            }
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack == null) {
            return null;
        }
        if (stack.size <= count) {
            setItem(inventory, slot, null);
            return stack;
        }
        ItemStack removed = new ItemStack(stack.id, count, stack.metadata);
        stack.size -= count;
        return removed;
    }
}
