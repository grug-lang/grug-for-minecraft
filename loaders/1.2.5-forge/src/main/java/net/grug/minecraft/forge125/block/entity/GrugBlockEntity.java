package net.grug.minecraft.forge125.block.entity;

import net.grug.minecraft.forge125.block.GrugBlock;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugObject;
import net.minecraft.src.Block;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.IInventory;
import net.minecraft.src.InventoryBasic;
import net.minecraft.src.ItemStack;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.NBTTagList;
import net.minecraft.src.TileEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The tile entity behind every {@link GrugBlock}: a grug-scripted inventory and tick hook.
 *
 * <p>1.2.5 instantiates tile entities generically (through {@code TileEntity.createAndLoadEntity}
 * and a registered class mapping), so the real inventory size is not known until the block is in
 * the world. The backing array therefore starts empty and is sized lazily from the block.
 */
public class GrugBlockEntity extends TileEntity implements IInventory {
    private long entityHandle = 0;
    private long tickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean initStarted = false;

    /**
     * The host entities this block entity's member scope created, rooted here so they live exactly
     * as long as the block entity and are freed with it (the legacy GrugEntity.childEntities idea).
     */
    public final List<GrugObject> childEntities = new ArrayList<>();

    private ItemStack[] stacks = new ItemStack[0];
    private boolean sized = false;

    /**
     * The cached recipe result, in a one-slot container of its own so it never takes an inventory
     * address: {@link #getSizeInventory()} reports only the grid. The adapter's recipe lookup fills
     * it, the GUI result slot renders it, and taking from either path removes from this same slot.
     */
    private final InventoryBasic resultInventory = new InventoryBasic("Crafting Result", 1);

    private void ensureSized() {
        if (sized) return;
        sized = trySizeInventory();
    }

    /** The one-slot container holding the cached crafting result. */
    public IInventory getResultInventory() {
        return resultInventory;
    }

    /** Caches the recipe result, or clears it when {@code stack} is null. */
    public void setResultStack(ItemStack stack) {
        resultInventory.setInventorySlotContents(0, stack);
    }

    /** Removes up to {@code amount} from the cached result and returns what was removed. */
    public ItemStack takeResultStack(int amount) {
        return resultInventory.decrStackSize(0, amount);
    }

    /** The cached result as it stands, or null when there is none. */
    public ItemStack getResultStack() {
        ItemStack stack = resultInventory.getStackInSlot(0);
        return stack != null && stack.stackSize > 0 ? stack : null;
    }

    private void initGrug() {
        if (entityHandle != 0 || initStarted) return;

        initStarted = true;
        entityHandle = createGrugEntity();

        if (entityHandle != 0) {
            tickFnId = Grug.getExportFnId("BlockEntity", "tick");
            long initFnId = Grug.getExportFnId("BlockEntity", "init");
            if (initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                callExportFn(initFnId);
            }
        }
    }

    /**
     * Runs one export function on this block entity's grug entity, with its host entities rooted
     * and {@code me} resolving to this block entity.
     *
     * <p>{@code me} is bound for the call because init is where a script first reaches for it: a
     * block entity whose member scope only declares has nothing bound yet.
     */
    private void callExportFn(long fnId) {
        List<GrugObject> oldFnEntities = Grug.fnEntities;
        Grug.fnEntities = childEntities;
        Grug.currentlyInitializingBlockEntity = this;
        Grug.callExportFn(entityHandle, fnId);
        Grug.currentlyInitializingBlockEntity = null;
        Grug.fnEntities = oldFnEntities;
    }

    @GrugGenerated(
            "inventory/entity setup guards: the world and block are not controllable in tests")
    private boolean trySizeInventory() {
        if (worldObj == null) return false;

        Block block = Block.blocksList[worldObj.getBlockId(xCoord, yCoord, zCoord)];
        if (!(block instanceof GrugBlock)) return false;

        stacks = new ItemStack[((GrugBlock) block).getInventorySize()];
        return true;
    }

    @GrugGenerated(
            "inventory/entity setup guards: the world and block are not controllable in tests")
    private long createGrugEntity() {
        ensureSized();
        if (worldObj == null) return 0;

        Block block = Block.blocksList[worldObj.getBlockId(xCoord, yCoord, zCoord)];
        if (!(block instanceof GrugBlock)) return 0;

        long fileId = ((GrugBlock) block).getEntityFileId();
        if (fileId == Grug.INVALID_GRUG_FILE_ID) return 0;

        Grug.currentlyInitializingBlockEntity = this;
        List<GrugObject> oldFnEntities = Grug.fnEntities;
        Grug.fnEntities = childEntities;
        long handle = Grug.createEntity(fileId);
        Grug.fnEntities = oldFnEntities;
        Grug.currentlyInitializingBlockEntity = null;
        return handle;
    }

    @Override
    public void updateEntity() {
        super.updateEntity();

        if (entityHandle == 0) {
            initGrug();
        }

        if (entityHandle != 0 && tickFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            List<GrugObject> oldFnEntities = Grug.fnEntities;
            Grug.fnEntities = new ArrayList<GrugObject>();

            Grug.callExportFn(entityHandle, tickFnId);

            Grug.fnEntities = oldFnEntities;
        }
    }

    /**
     * Called when a neighbouring block changes, so the script can re-evaluate its connections
     * instead of polling every tick.
     */
    public void notifyNeighborChanged() {
        initGrug();

        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "on_neighbor_change");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_on_neighbor_change(entityHandle);
            }
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();

        teardownGrugEntity();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();

        // 1.2.5 does not call invalidate() when a chunk unloads; it calls this instead and keeps
        // the tile, re-adding it on the next load. Destroy the VM entity so it cannot outlive the
        // tile (a stale entity fails to resolve its own handle on the next hot reload), and clear
        // initStarted so updateEntity builds a fresh one once the chunk comes back.
        teardownGrugEntity();
        initStarted = false;
    }

    private void teardownGrugEntity() {
        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
            childEntities.clear();
        }
    }

    // --- Inventory Implementation ---

    @Override
    public int getSizeInventory() {
        ensureSized();
        return stacks.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length) return null;
        return stacks[slot];
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length || stacks[slot] == null) return null;

        ItemStack result;
        if (stacks[slot].stackSize <= amount) {
            result = stacks[slot];
            stacks[slot] = null;
        } else {
            result = stacks[slot].splitStack(amount);
            if (stacks[slot].stackSize == 0) {
                stacks[slot] = null;
            }
        }

        onInventoryChanged();

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
    public void setInventorySlotContents(int slot, ItemStack stack) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length) return;

        stacks[slot] = stack;
        if (stack != null && stack.stackSize > getInventoryStackLimit()) {
            stack.stackSize = getInventoryStackLimit();
        }

        onInventoryChanged();

        initGrug();

        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "item_inserted");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_item_inserted(
                        entityHandle, slot, stack != null ? stack.stackSize : 0);
            }
        }
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length) return null;

        ItemStack stack = stacks[slot];
        stacks[slot] = null;
        return stack;
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
    public String getInvName() {
        return "Grug Inventory";
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return withinReach(player);
    }

    @GrugGenerated("container reachability: the game only asks with the GUI open")
    private boolean withinReach(EntityPlayer player) {
        return worldObj.getBlockTileEntity(xCoord, yCoord, zCoord) == this
                && player.getDistanceSq(xCoord + 0.5D, yCoord + 0.5D, zCoord + 0.5D) <= 64.0D;
    }

    @Override
    public void openChest() {}

    @Override
    public void closeChest() {}

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        // Recover the size from NBT before loading items, so a load does not depend on the world
        // being present (it is not, during a test's NBT round trip).
        if (nbt.hasKey("GrugInvSize")) {
            stacks = new ItemStack[nbt.getInteger("GrugInvSize")];
            sized = true;
        } else {
            ensureSized();
        }

        NBTTagList items = nbt.getTagList("Items");
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound itemNbt = (NBTTagCompound) items.tagAt(i);
            int slot = itemNbt.getByte("Slot") & 255;
            if (slot < stacks.length) {
                stacks[slot] = ItemStack.loadItemStackFromNBT(itemNbt);
            }
        }

        resultInventory.setInventorySlotContents(
                0,
                nbt.hasKey("GrugResult")
                        ? ItemStack.loadItemStackFromNBT(nbt.getCompoundTag("GrugResult"))
                        : null);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        ensureSized();

        // Save the size so readFromNBT does not need the world object.
        nbt.setInteger("GrugInvSize", stacks.length);

        NBTTagList items = new NBTTagList();
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] != null) {
                NBTTagCompound itemNbt = new NBTTagCompound();
                itemNbt.setByte("Slot", (byte) i);
                stacks[i].writeToNBT(itemNbt);
                items.appendTag(itemNbt);
            }
        }
        nbt.setTag("Items", items);

        ItemStack resultStack = resultInventory.getStackInSlot(0);
        if (resultStack != null) {
            NBTTagCompound resultNbt = new NBTTagCompound();
            resultStack.writeToNBT(resultNbt);
            nbt.setTag("GrugResult", resultNbt);
        }
    }
}
