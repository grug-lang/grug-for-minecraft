package net.grug.minecraft.block.entity;

import net.grug.minecraft.block.GrugBlock;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugObject;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
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

    // Backing storage for the Inventory interface. Starts unsized because this
    // class is instantiated generically (via reflection during deserialization,
    // see InitListener#registerBlockEntities) before we know which grug block
    // placed it, so the real size is resolved lazily from the GrugBlock once
    // world/x/y/z are available.
    private ItemStack[] stacks = new ItemStack[0];
    private boolean sized = false;

    private void ensureSized() {
        if (sized || world == null)
            return;

        Block block = Block.BLOCKS[world.getBlockId(x, y, z)];
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

        Block block = Block.BLOCKS[world.getBlockId(x, y, z)];
        if (!(block instanceof GrugBlock))
            return;

        long fileId = ((GrugBlock) block).getEntityFileId();

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

    @Override
    public void markRemoved() {
        super.markRemoved();

        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
        }
    }

    // Inventory

    @Override
    public int size() {
        ensureSized();
        return stacks.length;
    }

    @Override
    public ItemStack getStack(int slot) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length)
            return null;
        return stacks[slot];
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length || stacks[slot] == null)
            return null;

        ItemStack result;
        if (stacks[slot].count <= amount) {
            result = stacks[slot];
            stacks[slot] = null;
        } else {
            result = stacks[slot].split(amount);
            if (stacks[slot].count == 0) {
                stacks[slot] = null;
            }
        }

        markDirty();
        return result;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length)
            return;

        stacks[slot] = stack;
        if (stack != null && stack.count > getMaxCountPerStack()) {
            stack.count = getMaxCountPerStack();
        }

        markDirty();
    }

    @Override
    public String getName() {
        return "Grug Inventory";
    }

    @Override
    public int getMaxCountPerStack() {
        return 64;
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return world.getBlockEntity(x, y, z) == this
                && player.getSquaredDistance(x + 0.5D, y + 0.5D, z + 0.5D) <= 64D;
    }

    @Override
    public void markDirty() {
        super.markDirty();
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);

        // Recover the size from NBT before attempting to load items,
        // completely bypassing the world == null issue.
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

        // Save the size so readNbt doesn't need the world object.
        nbt.putInt("GrugInvSize", stacks.length);

        NbtList items = new NbtList();
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] != null) {
                NbtCompound itemNbt = new NbtCompound();
                itemNbt.putByte("Slot", (byte) i);
                stacks[i].writeNbt(itemNbt);
                items.add(itemNbt);
            }
        }
        nbt.put("Items", items);
    }
}
