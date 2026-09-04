package net.grug.minecraft.ornithe.block.entity;

import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
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

    private void ensureSized() {
        if (sized || world == null)
            return;

        Block block = Block.BY_ID[world.getBlock(x, y, z)];
        if (!(block instanceof GrugBlock grugBlock))
            return;

        stacks = new ItemStack[grugBlock.getInventorySize()];
        sized = true;
    }

    private void initGrug() {
        if (entityHandle != 0 || initAttempted)
            return;
        initAttempted = true;

        ensureSized();

        Block block = Block.BY_ID[world.getBlock(x, y, z)];
        if (!(block instanceof GrugBlock grugBlock))
            return;

        long fileId = grugBlock.getEntityFileId();
        if (fileId == Grug.INVALID_GRUG_FILE_ID)
            return;

        Grug.currentlyInitializingBlockEntity = this;
        entityHandle = Grug.createEntity(fileId);
        Grug.currentlyInitializingBlockEntity = null;

        if (entityHandle != 0) {
            tickFnId = Grug.getExportFnId("BlockEntity", "tick");
        }
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
        if (slot < 0 || slot >= stacks.length)
            return null;
        return stacks[slot];
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length || stacks[slot] == null)
            return null;

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
        if (slot < 0 || slot >= stacks.length)
            return;

        stacks[slot] = stack;
        if (stack != null && stack.size > getMaxStackSize()) {
            stack.size = getMaxStackSize();
        }

        markDirty();

        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "item_inserted");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_item_inserted(entityHandle, slot, stack != null ? stack.size : 0);
            }
        }
    }

    public void notifyOutputTaken(int slot, int amount) {
        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "output_taken");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_output_taken(entityHandle, slot, amount);
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
    }
}
