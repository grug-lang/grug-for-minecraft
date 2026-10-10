package net.grug.minecraft.stationapi.block.entity;

import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugObject;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

public class GrugBlockEntity extends BlockEntity implements Inventory {
    private long entityHandle = 0;
    private long tickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private long renderFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean renderFnIdResolved = false;
    private boolean initStarted = false;

    /**
     * The host entities this block entity's member scope created, rooted here so they live exactly
     * as long as the block entity and are freed with it (the legacy GrugEntity.childEntities idea).
     */
    public final List<GrugObject> childEntities = new ArrayList<>();

    // Backing storage for the Inventory interface. Starts unsized because this
    // class is instantiated generically (via reflection during deserialization,
    // see InitListener#registerBlockEntities) before we know which grug block
    // placed it, so the real size is resolved lazily from the GrugBlock once
    // world/x/y/z are available.
    private ItemStack[] stacks = new ItemStack[0];
    private boolean sized = false;

    /**
     * The cached recipe result, in a one-slot container of its own so it never takes an inventory
     * address: {@link #size()} reports only the grid. The adapter's recipe lookup fills it, the GUI
     * result slot renders it, and taking from either path removes from this same slot.
     */
    private final SimpleInventory resultInventory = new SimpleInventory("Crafting Result", 1);

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
        resultInventory.setStack(0, stack);
    }

    /** Removes up to {@code amount} from the cached result and returns what was removed. */
    public ItemStack takeResultStack(int amount) {
        return resultInventory.removeStack(0, amount);
    }

    /** The cached result as it stands, or null when there is none. */
    public ItemStack getResultStack() {
        ItemStack stack = resultInventory.getStack(0);
        return stack != null && stack.count > 0 ? stack : null;
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
        Grug.callExportFnWithOwner(entityHandle, fnId, childEntities, this);
    }

    @GrugGenerated(
            "inventory/entity setup guards: the world and block are not controllable in tests")
    private boolean trySizeInventory() {
        if (world == null) return false;

        Block block = Block.BLOCKS[world.getBlockId(x, y, z)];
        if (!(block instanceof GrugBlock grugBlock)) return false;

        stacks = new ItemStack[grugBlock.getInventorySize()];
        return true;
    }

    @GrugGenerated(
            "inventory/entity setup guards: the world and block are not controllable in tests")
    private long createGrugEntity() {
        ensureSized();
        if (world == null) return 0;

        Block block = Block.BLOCKS[world.getBlockId(x, y, z)];
        if (!(block instanceof GrugBlock)) return 0;

        long fileId = ((GrugBlock) block).getEntityFileId();
        if (fileId == Grug.INVALID_GRUG_FILE_ID) return 0;

        return Grug.createEntityWithOwner(fileId, childEntities, this);
    }

    @Override
    public void tick() {
        super.tick();

        if (entityHandle == 0) {
            initGrug();
        }

        if (entityHandle != 0 && tickFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            Grug.callExportFnWithOwner(entityHandle, tickFnId, new ArrayList<>(), null);
        }
    }

    /**
     * This block entity's grug entity handle, or 0 when it has none yet.
     *
     * <p>A chunk can be compiled before the block entity has ticked, which is when its geometry is
     * drawn, so the render path inits rather than assuming a tick got there first.
     */
    public long getGrugEntityHandle() {
        initGrug();
        return entityHandle;
    }

    /**
     * The id of the {@code render} export, looked up once and remembered, because a chunk compile
     * asks for it per block and the lookup goes through the state lock.
     *
     * <p>This is never {@link Grug#INVALID_GRUG_EXPORT_FN_ID}. grug builds its table of export ids
     * from {@code mod_api.json} rather than from the files that are loaded, so the id resolves
     * whatever any script exports. Whether <em>this</em> block entity's script has one is a
     * different question, and the engine answers it by declining the call without reporting, so it
     * shows up as a pass that drew nothing. See {@link net.grug.minecraft.grug.GrugBlockGeometry}.
     */
    public long getRenderFnId() {
        if (!renderFnIdResolved) {
            renderFnId = Grug.getExportFnId("BlockEntity", "render");
            renderFnIdResolved = true;
        }
        return renderFnId;
    }

    @Override
    public void markRemoved() {
        super.markRemoved();

        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
            childEntities.clear();
        }
        // Reset with the handle, so a tile that is re-inited after a hot reload resolves its render
        // function again rather than answering from a cache the destroyed entity left behind.
        renderFnIdResolved = false;
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
        if (slot < 0 || slot >= stacks.length) return null;
        return stacks[slot];
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length || stacks[slot] == null) return null;

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
    public void setStack(int slot, ItemStack stack) {
        ensureSized();
        if (slot < 0 || slot >= stacks.length) return;

        stacks[slot] = stack;
        if (stack != null && stack.count > getMaxCountPerStack()) {
            stack.count = getMaxCountPerStack();
        }

        markDirty();

        initGrug();

        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "item_inserted");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_item_inserted(
                        entityHandle, slot, stack != null ? stack.count : 0);
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
    public String getName() {
        return "Grug Inventory";
    }

    @Override
    public int getMaxCountPerStack() {
        return 64;
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return withinReach(player);
    }

    @GrugGenerated("container reachability: the game only asks with the GUI open")
    private boolean withinReach(PlayerEntity player) {
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

        resultInventory.setStack(
                0,
                nbt.contains("GrugResult") ? new ItemStack(nbt.getCompound("GrugResult")) : null);
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

        ItemStack resultStack = resultInventory.getStack(0);
        if (resultStack != null) {
            nbt.put("GrugResult", resultStack.writeNbt(new NbtCompound()));
        }
    }
}
