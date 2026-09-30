package net.grug.minecraft.ornithe.block.entity;

import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugObject;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

public class GrugBlockEntity extends BlockEntity implements Inventory {
    private long entityHandle = 0;
    private long tickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean initAttempted = false;

    private ItemStack[] stacks = new ItemStack[0];
    private boolean sized = false;

    /**
     * The cached recipe result, in a one-slot container of its own so it never takes an inventory
     * address: {@link #getSize()} reports only the grid. The adapter's recipe lookup fills it, the
     * GUI result slot renders it, and taking from either path removes from this same slot.
     */
    private final ResultInventory resultInventory = new ResultInventory();

    private void ensureSized() {
        if (sized) return;
        sized = trySizeInventory();
    }

    /** The one-slot container holding the cached crafting result. */
    public Inventory getResultInventory() {
        return resultInventory;
    }

    /** Caches the recipe result, or clears it when {@code stack} is null. */
    public void setResultStack(ItemStack stack) {
        resultInventory.setItem(0, stack);
    }

    /** Removes up to {@code amount} from the cached result and returns what was removed. */
    public ItemStack takeResultStack(int amount) {
        return resultInventory.removeItem(0, amount);
    }

    /**
     * Alpha has no reusable one-slot inventory, unlike the newer loaders' SimpleInventory, so the
     * holder implements {@link Inventory} directly.
     */
    private class ResultInventory implements Inventory {
        private ItemStack stack = null;

        @Override
        public int getSize() {
            return 1;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot == 0 ? stack : null;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            if (slot != 0 || stack == null) return null;
            ItemStack result;
            if (stack.size <= amount) {
                result = stack;
                stack = null;
            } else {
                result = stack.split(amount);
                if (stack.size == 0) stack = null;
            }
            return result;
        }

        @Override
        public void setItem(int slot, ItemStack itemStack) {
            if (slot == 0) stack = itemStack;
        }

        @Override
        public String getInventoryName() {
            return "Crafting Result";
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public void markDirty() {
            GrugBlockEntity.this.markDirty();
        }
    }

    private void initGrug() {
        if (entityHandle != 0 || initAttempted) return;

        initAttempted = true;
        entityHandle = createGrugEntity();

        if (entityHandle != 0) {
            tickFnId = Grug.getExportFnId("BlockEntity", "tick");
        }
    }

    @GrugGenerated(
            "inventory/entity setup guards: the world and block are not controllable in tests")
    private boolean trySizeInventory() {
        if (world == null) return false;

        Block block = Block.BY_ID[world.getBlock(x, y, z)];
        if (!(block instanceof GrugBlock grugBlock)) return false;

        stacks = new ItemStack[grugBlock.getInventorySize()];
        return true;
    }

    @GrugGenerated(
            "inventory/entity setup guards: the world and block are not controllable in tests")
    private long createGrugEntity() {
        ensureSized();
        if (world == null) return 0;

        Block block = Block.BY_ID[world.getBlock(x, y, z)];
        if (!(block instanceof GrugBlock grugBlock)) return 0;

        long fileId = grugBlock.getEntityFileId();
        if (fileId == Grug.INVALID_GRUG_FILE_ID) return 0;

        Grug.currentlyInitializingBlockEntity = this;
        long handle = Grug.createEntity(fileId);
        Grug.currentlyInitializingBlockEntity = null;
        return handle;
    }

    @Override
    public void tick() {
        super.tick();

        if (entityHandle == 0) {
            initGrug();
        }

        if (entityHandle != 0 && tickFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            List<GrugObject> oldFnEntities = Grug.fnEntities;
            Grug.fnEntities = new ArrayList<>();

            Grug.callExportFn(entityHandle, tickFnId);

            Grug.fnEntities = oldFnEntities;
        }
    }

    // --- Inventory Implementation ---

    @Override
    public int getSize() {
        ensureSized();
        return stacks.length;
    }

    @Override
    public ItemStack getItem(int slot) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length) return null;
        return stacks[slot];
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length || stacks[slot] == null) return null;

        ItemStack result;
        if (stacks[slot].size <= amount) {
            result = stacks[slot];
            stacks[slot] = null;
        } else {
            result = stacks[slot].split(amount);
            if (stacks[slot].size == 0) {
                stacks[slot] = null;
            }
        }

        markDirty();

        initGrug();

        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "item_extracted");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_item_extracted(entityHandle, slot, amount);
            }
        }

        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length) return;

        stacks[slot] = stack;
        if (stack != null && stack.size > getMaxStackSize()) {
            stack.size = getMaxStackSize();
        }

        markDirty();

        initGrug();

        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "item_inserted");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_item_inserted(
                        entityHandle, slot, stack != null ? stack.size : 0);
            }
        }
    }

    public void notifyOutputTaken(int amount) {
        initGrug();

        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "output_taken");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_output_taken(entityHandle, amount);
            }
        }
    }

    @Override
    public String getInventoryName() {
        return "Grug Inventory";
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    public boolean isValid(PlayerEntity player) {
        return withinReach(player);
    }

    @GrugGenerated("container reachability: the game only asks with the GUI open")
    private boolean withinReach(PlayerEntity player) {
        return world.getBlockEntity(x, y, z) == this
                && player.squaredDistanceTo(x + 0.5D, y + 0.5D, z + 0.5D) <= 64D;
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);

        if (nbt.contains("GrugInvSize")) {
            stacks = new ItemStack[nbt.getInt("GrugInvSize")];
            sized = true;
        } else {
            ensureSized();
        }

        NbtList items = nbt.getList("Items");
        for (int i = 0; i < items.size(); i++) {
            NbtCompound itemNbt = (NbtCompound) items.get(i);
            int slot = itemNbt.getByte("Slot") & 255;
            if (slot < stacks.length) {
                stacks[slot] = new ItemStack(itemNbt);
            }
        }

        resultInventory.setItem(
                0,
                nbt.contains("GrugResult") ? new ItemStack(nbt.getCompound("GrugResult")) : null);
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        ensureSized();

        nbt.putInt("GrugInvSize", stacks.length);

        NbtList items = new NbtList();
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] != null) {
                NbtCompound itemNbt = new NbtCompound();
                itemNbt.putByte("Slot", (byte) i);
                stacks[i].writeNbt(itemNbt);
                items.addElement(itemNbt);
            }
        }
        nbt.put("Items", items);

        ItemStack resultStack = resultInventory.getItem(0);
        if (resultStack != null) {
            nbt.put("GrugResult", resultStack.writeNbt(new NbtCompound()));
        }
    }
}
