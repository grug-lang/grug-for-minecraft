package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The cursor write of {@code TestTickContext.set_carried_item} on Alpha (#1850).
 *
 * <p>{@code ModLoaderAdapter.setCarriedItem} documents a null item as clearing the cursor, but a
 * grug call cannot pass one: the API takes an {@code Item}, not an option. The arm is therefore
 * latent, and a grug test in {@code mods/coverage/} cannot reach it. The two writes this server
 * makes, the menu's own cursor and the carried stack, are therefore exercised here through the
 * helpers it calls.
 *
 * <p>Alpha's {@code ItemStack(Item)} reads {@code item.id}, so a null item used to throw before
 * either path could clear.
 */
class GrugAlphaCursorTest {

    @Test
    void aNullItemClearsTheMenuCursorRatherThanBuildingAStack() {
        GrugAlphaMenu menu = menu();
        menu.cursor = new ItemStack(Item.DIAMOND, 5);

        ServerOrnitheAdapter.setMenuCursor(menu, null);
        assertNull(menu.cursor, "a null item has to clear the menu's cursor");

        ServerOrnitheAdapter.setMenuCursor(menu, Item.DIAMOND);
        assertNotNull(menu.cursor, "an item still sets the menu's cursor");
        assertEquals(Item.DIAMOND, menu.cursor.getItem());
    }

    @Test
    void aNullItemRemovesTheCarriedStackRatherThanBuildingAStack() {
        Map<Object, ItemStack> carried = new HashMap<>();
        Object player = new Object();

        assertNotNull(
                ServerOrnitheAdapter.putCarried(carried, player, Item.DIAMOND),
                "an item still sets the carried stack");
        assertNotNull(carried.get(player));

        assertNull(
                ServerOrnitheAdapter.putCarried(carried, player, null),
                "a null item has to clear the carried stack");
        assertFalse(
                carried.containsKey(player), "a null item has to remove the carried stack's entry");
    }

    /** A menu whose one crafting grid addresses slots 0 to 8 of a fresh inventory. */
    private static GrugAlphaMenu menu() {
        Inventory inventory =
                new Inventory() {
                    private final ItemStack[] slots = new ItemStack[9];

                    @Override
                    public int getSize() {
                        return slots.length;
                    }

                    @Override
                    public ItemStack getItem(int slot) {
                        return slots[slot];
                    }

                    @Override
                    public ItemStack removeItem(int slot, int amount) {
                        return null;
                    }

                    @Override
                    public void setItem(int slot, ItemStack stack) {
                        slots[slot] = stack;
                    }

                    @Override
                    public String getInventoryName() {
                        return "cursor test inventory";
                    }

                    @Override
                    public int getMaxStackSize() {
                        return 64;
                    }

                    @Override
                    public void markDirty() {}
                };
        GrugGuiBuilder layout = new GrugGuiBuilder("textures/gui/test.png");
        layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 30, 17));
        return GrugAlphaMenu.build(null, inventory, layout);
    }
}
