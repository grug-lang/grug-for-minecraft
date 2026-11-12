package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The write a merge click makes to the slot under the pointer (#1223).
 *
 * <p>{@code GrugMenuClickTest} pins the amount a merge moves, but the amount only decides whether
 * the click writes the slot at all, and that is what marks the block entity dirty and fires its
 * {@code item_inserted} export. The click itself runs on the server of a joined client, which only
 * the dispatch-only multiplayer job reaches (a1's own click test is listed unsupported), so the
 * write is observed here instead. The inventory records each {@code setItem} it is handed, which is
 * the call the merge's dirty mark and export hang off; the export itself is the block entity's own
 * and needs the grug VM.
 *
 * <p>{@link GrugInventoryAccess} picks the client's {@code setItem} or the server's reflected one
 * from the loader environment, which a plain test JVM has no launcher for, so a launcher that
 * reports a client is installed before the click class first reads it.
 */
class GrugAlphaMenuClickTest {
    /** A diamond, which both sides name by the same id and which stacks to 64. */
    private static final int DIAMOND = 264;

    @BeforeAll
    static void installTheClientEnvironmentTheWriteNeeds() {
        if (FabricLauncherBase.getLauncher() == null) {
            FabricLauncherBase.setLauncher(
                    (FabricLauncher)
                            Proxy.newProxyInstance(
                                    FabricLauncher.class.getClassLoader(),
                                    new Class<?>[] {FabricLauncher.class},
                                    (proxy, method, args) -> {
                                        switch (method.getName()) {
                                            case "getEnvironmentType":
                                                return EnvType.CLIENT;
                                            case "isDevelopment":
                                                return true;
                                            default:
                                                throw new UnsupportedOperationException(
                                                        "the write asks nothing else of the"
                                                                + " launcher: "
                                                                + method.getName());
                                        }
                                    }));
        }
        assertEquals(
                EnvType.CLIENT,
                FabricLoader.getInstance().getEnvironmentType(),
                "the write observes the client's setItem, so the test needs a client launcher");
    }

    @Test
    void aMergeWritesTheMergedStackThroughTheInventory() {
        RecordingInventory inventory = new RecordingInventory();
        GrugAlphaMenu menu = menu(inventory);
        inventory.put(0, new ItemStack(DIAMOND, 8, 0));
        menu.cursor = new ItemStack(DIAMOND, 16, 0);

        menu.click(null, 0, 0);

        assertEquals(
                1,
                inventory.writes.size(),
                "a merge has to write the slot, or the block entity is never marked dirty and its"
                        + " item_inserted export never runs");
        assertEquals(0, inventory.writes.get(0).slot);
        assertEquals(24, inventory.writes.get(0).stack.size);
        assertNull(menu.cursor, "the whole carried stack moved into the slot");
    }

    @Test
    void aMergeIntoAFullSlotWritesNothing() {
        RecordingInventory inventory = new RecordingInventory();
        GrugAlphaMenu menu = menu(inventory);
        inventory.put(0, new ItemStack(DIAMOND, 64, 0));
        menu.cursor = new ItemStack(DIAMOND, 16, 0);

        menu.click(null, 0, 0);

        assertTrue(
                inventory.writes.isEmpty(),
                "a merge that moves nothing must not write, dirty or export");
        assertEquals(64, inventory.getItem(0).size);
        assertEquals(16, menu.cursor.size);
    }

    @Test
    void aRightClickMergeWritesTheOneItemItMoves() {
        RecordingInventory inventory = new RecordingInventory();
        GrugAlphaMenu menu = menu(inventory);
        inventory.put(0, new ItemStack(DIAMOND, 8, 0));
        menu.cursor = new ItemStack(DIAMOND, 16, 0);

        menu.click(null, 0, 1);

        assertEquals(1, inventory.writes.size());
        assertEquals(9, inventory.getItem(0).size);
        assertEquals(15, menu.cursor.size);
    }

    /** A menu whose one crafting grid addresses slots 0 to 8 of {@code inventory}. */
    private static GrugAlphaMenu menu(Inventory inventory) {
        GrugGuiBuilder layout = new GrugGuiBuilder("textures/gui/test.png");
        layout.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(0, 30, 17));
        return GrugAlphaMenu.build(null, inventory, layout);
    }

    /** One slot write a click made through {@link GrugInventoryAccess#setItem}. */
    private static final class Write {
        private final int slot;
        private final ItemStack stack;

        Write(int slot, ItemStack stack) {
            this.slot = slot;
            this.stack = stack;
        }
    }

    /** The inventory under the pointer, which records every write a click makes to it. */
    private static final class RecordingInventory implements Inventory {
        private final ItemStack[] slots = new ItemStack[9];
        private final List<Write> writes = new ArrayList<>();

        /** Puts a stack in a slot as the test's setup, which is not a write a click made. */
        void put(int slot, ItemStack stack) {
            slots[slot] = stack;
        }

        @Override
        public int getSize() {
            return slots.length;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot >= 0 && slot < slots.length ? slots[slot] : null;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return null;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            writes.add(new Write(slot, stack));
            slots[slot] = stack;
        }

        @Override
        public String getInventoryName() {
            return "recording inventory";
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public void markDirty() {}
    }
}
