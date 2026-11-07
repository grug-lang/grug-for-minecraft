package net.grug.minecraft.ornithe;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.entity.mob.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One grug GUI's slot table on Alpha, built from the same layout on the server and on the joined
 * client (#1098).
 *
 * <p>Alpha has no menu or slot class of its own: its container screen owns the slots, and a screen
 * exists only on a client. So this class holds only what both sides need, the ordered table of
 * (inventory, slot, position) the vanilla click path uses, and both sides build it from the same
 * layout with {@link #build}. That is what keeps the slot a click names the slot the other side
 * means, which is the one thing that must not drift.
 *
 * <p>The table is the one the client built before this class existed: the crafting grids in layout
 * order, then the crafting results, then the player's main inventory and its hotbar. A server's
 * {@code PlayerInventory} has no cursor item of its own, so the cursor a server-held menu applies a
 * click to lives in {@link #cursor}; the client's cursor stays where that game keeps it.
 *
 * <p>{@link #build} and {@link Slot} are measured by the coverage gate, because a singleplayer GUI
 * test reaches them through the client's screen constructor. The click algorithm, the content
 * packet's reads and the joined-client arms below are not: they need a dedicated server or a
 * server-opened screen, so each carries {@link GrugGenerated}.
 */
public final class GrugAlphaMenu {
    /**
     * The stack size a slot accepts. Every inventory a grug GUI binds a slot to is an ordinary
     * container, whose size is this; the a0.2.1 server's {@code Inventory} interface declares no
     * {@code getMaxStackSize}, so it cannot be asked there.
     */
    public static final int MAX_STACK_SIZE = 64;

    /** One slot of the table: which inventory and index it reads, and where the screen draws it. */
    public static final class Slot {
        public final Inventory inventory;
        public final int index;
        public final int x;
        public final int y;

        /**
         * Whether this is a crafting result, which accepts nothing and tells the block entity when
         * something is taken from it.
         */
        public final boolean result;

        Slot(Inventory inventory, int index, int x, int y, boolean result) {
            this.inventory = inventory;
            this.index = index;
            this.x = x;
            this.y = y;
            this.result = result;
        }
    }

    public final List<Slot> slots = new ArrayList<>();
    public final Inventory blockInventory;
    public final PlayerInventory playerInventory;

    /** The block entity behind {@link #blockInventory}, or null for a client's placeholder. */
    public final GrugBlockEntity blockEntity;

    /** The one-slot container a crafting result is drawn from. */
    public final Inventory resultInventory;

    /** The window id the server installed this menu under, and -1 on a singleplayer client. */
    public int windowId = -1;

    /** The stack a server-held menu's next click carries; a client keeps its own. */
    public ItemStack cursor = null;

    private GrugAlphaMenu(
            Inventory blockInventory,
            PlayerInventory playerInventory,
            Inventory resultInventory,
            GrugBlockEntity blockEntity) {
        this.blockInventory = blockInventory;
        this.playerInventory = playerInventory;
        this.resultInventory = resultInventory;
        this.blockEntity = blockEntity;
    }

    /**
     * Builds the slot table from {@code layout}. The order of the loops is the wire format: the
     * client's screen and the server's table both come from here, so neither can reorder them.
     */
    public static GrugAlphaMenu build(
            PlayerInventory playerInventory, Inventory blockInventory, GrugGuiBuilder layout) {
        GrugAlphaMenu menu =
                new GrugAlphaMenu(
                        blockInventory,
                        playerInventory,
                        resultInventoryOf(blockInventory),
                        blockEntityOf(blockInventory));

        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    menu.slots.add(
                            new Slot(
                                    blockInventory,
                                    grid.startSlot() + col + row * 3,
                                    grid.x() + col * 18,
                                    grid.y() + row * 18,
                                    false));
                }
            }
        }

        for (GrugGuiBuilder.CraftingResultDef res : layout.craftingResults) {
            menu.slots.add(new Slot(menu.resultInventory, 0, res.x(), res.y(), true));
        }

        if (layout.hasPlayerInventory) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    menu.slots.add(
                            new Slot(
                                    playerInventory,
                                    col + row * 9 + 9,
                                    layout.playerInvX + col * 18,
                                    layout.playerInvY + row * 18,
                                    false));
                }
            }
            for (int col = 0; col < 9; col++) {
                menu.slots.add(
                        new Slot(
                                playerInventory,
                                col,
                                layout.hotbarX + col * 18,
                                layout.hotbarY,
                                false));
            }
        }
        return menu;
    }

    /**
     * The result inventory a menu draws its result slots from: the block entity's own, or a
     * placeholder for a joined client whose block entity has not arrived (#425).
     */
    @GrugGenerated("the placeholder arm: only a joined client whose block entity has not arrived")
    private static Inventory resultInventoryOf(Inventory blockInventory) {
        return blockInventory instanceof GrugBlockEntity
                ? ((GrugBlockEntity) blockInventory).getResultInventory()
                : new PlaceholderInventory(1);
    }

    /**
     * The block entity behind {@code blockInventory}, or null for a joined client's placeholder.
     */
    @GrugGenerated("the null arm: only a joined client whose block entity has not arrived")
    private static GrugBlockEntity blockEntityOf(Inventory blockInventory) {
        return blockInventory instanceof GrugBlockEntity ? (GrugBlockEntity) blockInventory : null;
    }

    /**
     * Applies the contents a server sent for {@code menu}: one entry per slot, in the order {@link
     * #build} makes them. The inventory writes go through {@link GrugInventoryAccess}, which is the
     * same call on both sides.
     */
    @GrugGenerated("only a server's content packet applies one: no singleplayer run reaches it")
    public static void applyContents(GrugAlphaMenu menu, ItemStack[] contents) {
        int count = Math.min(contents.length, menu.slots.size());
        for (int i = 0; i < count; i++) {
            Slot slot = menu.slots.get(i);
            GrugInventoryAccess.setItem(slot.inventory, slot.index, contents[i]);
        }
    }

    /** The stack {@code stack} holds in its slot, or null when the slot is empty. */
    @GrugGenerated("reads a slot for a server's content packet: no singleplayer run sends one")
    public static ItemStack itemOf(Slot slot) {
        return slot.inventory.getItem(slot.index);
    }

    /**
     * Applies one click of the client's screen to this menu, in the order the game's own screen
     * applies it (#1098): a slot under the pointer takes or places a stack, and a click that named
     * no slot drops the carried stack where the player is. {@code button} is 0 for a left click and
     * 1 for a right click.
     *
     * <p>The game's own handler is private to its screen and reads the client's cursor field, which
     * a server does not have, so the same decisions are written here over this menu's {@link
     * #cursor}. The branches follow {@code InventoryMenuScreen.mouseClicked} and the stack limits
     * follow what it asks an inventory and a stack for. A result slot accepts nothing, which is its
     * {@code isItemAllowed} answering false, and taking from it tells its block entity, which
     * restocks the grid.
     */
    @GrugGenerated("the server's click algorithm: a singleplayer screen uses the game's handler")
    public void click(PlayerEntity player, int index, int button) {
        if (button != 0 && button != 1) {
            return;
        }
        if (index < 0 || index >= slots.size()) {
            dropCursor(player, button);
            return;
        }
        Slot slot = slots.get(index);
        ItemStack inSlot = itemOf(slot);
        if (inSlot == null && cursor == null) {
            return;
        }
        if (inSlot != null && cursor == null) {
            int amount = button == 0 ? inSlot.size : (inSlot.size + 1) / 2;
            cursor = GrugInventoryAccess.removeItem(slot.inventory, slot.index, amount);
            if (inSlot.size == 0) {
                GrugInventoryAccess.setItem(slot.inventory, slot.index, null);
            }
            if (slot.result && blockEntity != null) {
                blockEntity.notifyOutputTaken(amount);
            }
        } else if (inSlot == null && cursor != null && !slot.result) {
            int amount = button == 0 ? cursor.size : 1;
            if (amount > MAX_STACK_SIZE) {
                amount = MAX_STACK_SIZE;
            }
            GrugInventoryAccess.setItem(slot.inventory, slot.index, split(cursor, amount));
            if (cursor.size == 0) {
                cursor = null;
            }
        } else if (inSlot != null && cursor != null) {
            if (!slot.result) {
                if (inSlot.id != cursor.id) {
                    if (cursor.size <= MAX_STACK_SIZE) {
                        GrugInventoryAccess.setItem(slot.inventory, slot.index, cursor);
                        cursor = inSlot;
                    }
                } else if (button == 0) {
                    int amount = cursor.size;
                    if (amount > MAX_STACK_SIZE - inSlot.size) {
                        amount = MAX_STACK_SIZE - inSlot.size;
                    }
                    if (amount > cursor.getMaxSize() - inSlot.size) {
                        amount = cursor.getMaxSize() - inSlot.size;
                    }
                    split(cursor, amount);
                    if (cursor.size == 0) {
                        cursor = null;
                    }
                    inSlot.size += amount;
                } else {
                    int amount = 1;
                    if (amount > MAX_STACK_SIZE - inSlot.size) {
                        amount = MAX_STACK_SIZE - inSlot.size;
                    }
                    if (amount > cursor.getMaxSize() - inSlot.size) {
                        amount = cursor.getMaxSize() - inSlot.size;
                    }
                    split(cursor, amount);
                    if (cursor.size == 0) {
                        cursor = null;
                    }
                    inSlot.size += amount;
                }
            } else if (inSlot.id == cursor.id && cursor.getMaxSize() > 1) {
                int amount = inSlot.size;
                if (amount > 0 && amount + cursor.size <= cursor.getMaxSize()) {
                    cursor.size += amount;
                    split(inSlot, amount);
                    if (inSlot.size == 0) {
                        GrugInventoryAccess.setItem(slot.inventory, slot.index, null);
                    }
                    if (blockEntity != null) {
                        blockEntity.notifyOutputTaken(amount);
                    }
                }
            }
        }
    }

    /**
     * Drops the carried stack where the player is, which the game's screen does for a click outside
     * the panel: a left click drops the whole stack and a right click one item (#1098).
     */
    @GrugGenerated("the click algorithm's outside-panel arm: no singleplayer run reaches it")
    private void dropCursor(PlayerEntity player, int button) {
        if (cursor == null) {
            return;
        }
        if (button == 0) {
            player.dropItem(cursor);
            cursor = null;
        } else {
            player.dropItem(split(cursor, 1));
            if (cursor.size == 0) {
                cursor = null;
            }
        }
    }

    /**
     * Takes {@code amount} off {@code stack} and returns them as a new stack. The a0.2.1 server's
     * {@code ItemStack} declares no {@code split}, so a call to it throws a {@code
     * NoSuchMethodError} there; the fields exist on both sides.
     */
    @GrugGenerated("the click algorithm's stack math: no singleplayer run reaches it")
    private static ItemStack split(ItemStack stack, int amount) {
        ItemStack removed = new ItemStack(stack.id, amount, stack.metadata);
        stack.size -= amount;
        return removed;
    }

    /**
     * A client's stand-in for an inventory of {@code size} when its block entity has not arrived
     * yet (#425). The contents come from the server's content packet either way, and a click that
     * changes them is the server's, so a placeholder changes nothing the script can observe.
     */
    @GrugGenerated("a placeholder for a block entity that has not arrived on a joined client")
    static final class PlaceholderInventory implements Inventory {
        private final ItemStack[] items;

        PlaceholderInventory(int size) {
            items = new ItemStack[size];
        }

        @Override
        public int getSize() {
            return items.length;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot >= 0 && slot < items.length ? items[slot] : null;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            if (slot < 0 || slot >= items.length || items[slot] == null) return null;
            ItemStack stack = items[slot];
            if (stack.size <= amount) {
                items[slot] = null;
                return stack;
            }
            ItemStack removed = new ItemStack(stack.id, amount, stack.metadata);
            stack.size -= amount;
            return removed;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (slot >= 0 && slot < items.length) items[slot] = stack;
        }

        @Override
        public String getInventoryName() {
            return "Grug Inventory";
        }

        @Override
        public int getMaxStackSize() {
            return MAX_STACK_SIZE;
        }

        @Override
        public void markDirty() {}
    }

    /**
     * The inventory a client draws a GUI over: its own copy of the block entity, or a placeholder
     * of the size the layout's grids need when that copy has not arrived yet.
     */
    @GrugGenerated("a joined client's own copy or its placeholder: no singleplayer run reaches it")
    public static Inventory clientInventory(
            Object worldObj, int x, int y, int z, GrugGuiBuilder layout) {
        if (worldObj instanceof net.minecraft.world.World) {
            net.minecraft.block.entity.BlockEntity blockEntity =
                    ((net.minecraft.world.World) worldObj).getBlockEntity(x, y, z);
            if (blockEntity instanceof Inventory) {
                return (Inventory) blockEntity;
            }
        }
        int size = 0;
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            size = Math.max(size, grid.startSlot() + 9);
        }
        return new PlaceholderInventory(size);
    }
}
