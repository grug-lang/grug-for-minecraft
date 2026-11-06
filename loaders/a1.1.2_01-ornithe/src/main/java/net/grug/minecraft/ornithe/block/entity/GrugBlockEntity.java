package net.grug.minecraft.ornithe.block.entity;

import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugStaleBlockEntity;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugObject;
import net.grug.minecraft.grug.GrugPower;
import net.grug.minecraft.grug.GrugSavedLists;
import net.grug.minecraft.grug.GrugSavedNumbers;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.GrugPowerNbt;
import net.grug.minecraft.ornithe.block.GrugSavedListsNbt;
import net.grug.minecraft.ornithe.block.GrugSavedNumbersNbt;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

public class GrugBlockEntity extends BlockEntity
        implements Inventory, GrugSavedNumbers.Holder, GrugSavedLists.Holder, GrugPower.Holder {
    private long entityHandle = 0;
    private long tickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private long renderFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean renderFnIdResolved = false;
    private long frameRenderFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean frameRenderFnIdResolved = false;
    private boolean initStarted = false;

    /**
     * The entity file the tile last tried to make its grug entity from, including {@link
     * Grug#INVALID_GRUG_FILE_ID} when the block named none, and {@link Grug#INVALID_GRUG_FILE_ID}
     * before anything was attempted. A client joined to a server is told only that a block changed,
     * and this version keeps the tile of the block that was there, so the tile can hold the
     * previous block's script: the render path compares this with the block now at the position.
     */
    private long initedEntityFileId = Grug.INVALID_GRUG_FILE_ID;

    /**
     * The host entities this block entity's member scope created, rooted here so they live exactly
     * as long as the block entity and are freed with it (the legacy GrugEntity.childEntities idea).
     */
    public final List<GrugObject> childEntities = new ArrayList<>();

    private ItemStack[] stacks = new ItemStack[0];
    private boolean sized = false;

    /**
     * The cached recipe result, in a one-slot container of its own so it never takes an inventory
     * address: {@link #getSize()} reports only the grid. The adapter's recipe lookup fills it, the
     * GUI result slot renders it, and taking from either path removes from this same slot.
     */
    private final ResultInventory resultInventory = new ResultInventory();

    /**
     * Takes {@code amount} off {@code stack} and returns them as a new stack. The a0.2.1 server's
     * {@code ItemStack} declares no {@code split}, so a call to it throws a {@code
     * NoSuchMethodError} there; the fields exist on both sides.
     */
    private static ItemStack splitStack(ItemStack stack, int amount) {
        ItemStack removed = new ItemStack(stack.id, amount, stack.metadata);
        stack.size -= amount;
        return removed;
    }

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
     * The cached result as it stands, or null when there is none. This game's empty stack is null,
     * so the check is the slot's own null rather than an emptiness test.
     */
    public ItemStack getResultStack() {
        return resultInventory.getItem(0);
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
                result = splitStack(stack, amount);
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
        if (entityHandle != 0 || initStarted) return;

        initStarted = true;
        // The attempt is recorded even when the block names no entity file, so a tile that stays
        // for the next block is inited from that block rather than keeping this attempt.
        initedEntityFileId = entityFileIdHere();
        entityHandle = createGrugEntity();

        if (entityHandle != 0) {
            tickFnId = Grug.getExportFnId("BlockEntity", "tick");
            long initFnId = Grug.getExportFnId("BlockEntity", "init");
            if (initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                GrugEntities.callBlockEntityInit(entityHandle, initFnId, childEntities, this);
            }
        }
    }

    @GrugGenerated(
            "inventory/entity setup guards: the world and block are not controllable in tests")
    private boolean trySizeInventory() {
        if (world == null) return false;

        Block block = Block.BY_ID[world.getBlock(x, y, z)];
        if (!(block instanceof GrugBlock)) return false;
        GrugBlock grugBlock = (GrugBlock) block;

        stacks = new ItemStack[grugBlock.getInventorySize()];
        return true;
    }

    /** The entity file the block at this position names, or INVALID when it names none. */
    @GrugGenerated("a tile outside a world, or under a non-grug block, cannot be forced in tests")
    private long entityFileIdHere() {
        if (world == null) return Grug.INVALID_GRUG_FILE_ID;

        Block block = Block.BY_ID[world.getBlock(x, y, z)];
        if (!(block instanceof GrugBlock)) return Grug.INVALID_GRUG_FILE_ID;
        return ((GrugBlock) block).getEntityFileId();
    }

    @GrugGenerated(
            "inventory/entity setup guards: the world and block are not controllable in tests")
    private long createGrugEntity() {
        ensureSized();
        long fileId = entityFileIdHere();
        if (fileId == Grug.INVALID_GRUG_FILE_ID) return 0;
        return GrugEntities.createEntityWithOwner(fileId, childEntities, this);
    }

    /**
     * Called when the game registers this block entity with a world and sets its position, which is
     * the moment 1.20.6's {@code onLoad} names. The grug entity is created here rather than on the
     * first {@code tick()} so a script's one-time setup runs at the same moment on every loader.
     */
    public void onRegistered() {
        initGrug();
    }

    @Override
    public void tick() {
        super.tick();

        if (entityHandle == 0) {
            initGrug();
        }

        if (entityHandle != 0 && tickFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            GrugEntities.callExportFnWithOwner(entityHandle, tickFnId, new ArrayList<>(), null);
        }
    }

    /**
     * This block entity's grug entity handle, or 0 when it has none yet.
     *
     * <p>A chunk can be compiled before the block entity has ticked, which is when its geometry is
     * drawn, so the render path inits rather than assuming a tick got there first.
     *
     * <p>The render path is also where a client notices that the tile is the one the game kept for
     * the block that was there, so it drops that block's grug entity before initing this one.
     */
    public long getGrugEntityHandle() {
        dropGrugEntityIfTheBlockChanged();
        initGrug();
        return entityHandle;
    }

    /**
     * Drops the grug entity when the block at this position is not the one it was made for.
     *
     * <p>A client joined to a server is told only that a block changed, and this version keeps the
     * tile: a grug block placed where another grug block was inherits its entity, which has the
     * previous block's members and exports, and the chunk compile then draws that script or, when
     * it has no {@code render}, nothing at all. The entity is dropped here and {@link #initGrug()}
     * makes the right one from the block that is there, or leaves none when that block names no
     * entity file, so nothing of the previous block draws for it.
     */
    @GrugGenerated("a client's kept tile: only a live joined client has one")
    private void dropGrugEntityIfTheBlockChanged() {
        // Nothing has been attempted before the first init, and shouldReinit compares the attempt
        // with the block, so a first render must not drop an entity nothing made.
        if (!initStarted) return;
        if (world == null) return;

        Block block = Block.BY_ID[world.getBlock(x, y, z)];
        if (!(block instanceof GrugBlock)) return;

        long blockEntityFileId = ((GrugBlock) block).getEntityFileId();
        if (!GrugStaleBlockEntity.shouldReinit(initedEntityFileId, blockEntityFileId)) return;

        destroyGrugEntity();
    }

    /**
     * The id of the {@code render} export, looked up once and remembered, because a chunk compile
     * asks for it per block and the lookup goes through the state lock.
     *
     * <p>This is never {@link Grug#INVALID_GRUG_EXPORT_FN_ID}. grug builds its table of export ids
     * from {@code mod_api.json} rather than from the files that are loaded, and this PR adds {@code
     * render} to that table, so the id resolves on every loader whatever any script exports.
     * Whether <em>this</em> block entity's script has one is a different question, and the engine
     * answers it by declining the call without reporting, so it shows up as a pass that drew
     * nothing. See {@link net.grug.minecraft.grug.GrugBlockGeometry}.
     */
    public long getRenderFnId() {
        if (!renderFnIdResolved) {
            renderFnId = Grug.getExportFnId("BlockEntity", "render");
            renderFnIdResolved = true;
        }
        return renderFnId;
    }

    /**
     * The id of the {@code render_frame} export, looked up once and remembered, because this is
     * asked for every frame the block is in view.
     *
     * <p>A block entity whose script has no {@code render_frame} is asked anyway, and the engine
     * declines the call without reporting, so a machine with nothing that moves between frames
     * needs no branch of its own.
     */
    public long getFrameRenderFnId() {
        if (!frameRenderFnIdResolved) {
            frameRenderFnId = Grug.getExportFnId("BlockEntity", "render_frame");
            frameRenderFnIdResolved = true;
        }
        return frameRenderFnId;
    }

    /**
     * Destroys the grug entity and releases the host entities it rooted.
     *
     * <p>Alpha's BlockEntity has no removal callback, so {@code GrugBlock.onRemoved} calls this
     * before the tile leaves the world. A removed tile whose native entity survives is re-inited by
     * a later hot reload, and the script then reads members Java has already collected.
     */
    public void destroyGrugEntity() {
        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
            GrugEntities.clearRoots(childEntities);
        }
        // Reset with the handle, so a tile that is re-inited after a hot reload resolves its render
        // function again rather than answering from a cache the destroyed entity left behind.
        renderFnIdResolved = false;
        // And so a tile a chunk unload removed, which keeps the same Java object, is inited again
        // by onRegistered() when the chunk comes back.
        initStarted = false;
        initedEntityFileId = Grug.INVALID_GRUG_FILE_ID;
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
            result = splitStack(stacks[slot], amount);
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

    /**
     * A saved number changed, so the chunk has to be saved with it, and the client's copy of the
     * block entity is sent the data the frame pass reads from it. On a dedicated server the script
     * writes this copy, and the client's holds the old numbers until it is told.
     */
    @Override
    public void markSavedNumbersChanged() {
        markDirty();
        // Only a change the client has not been sent, because a script may save the same value
        // every tick and each send is a packet.
        GrugSavedDataSync.changed(this);
    }

    /**
     * The energy this block entity holds changed, so the chunk has to be saved with it, and the
     * client's copy of the block entity is sent the store a frame pass may read from it.
     */
    @Override
    public void markEnergyChanged() {
        markDirty();
        GrugSavedDataSync.changed(this);
    }

    /**
     * Everything the frame pass reads from this block entity, written as the NBT the client's copy
     * is sent when a script changes it. The inventory is not part of it: it has its own window
     * packets.
     */
    public void writeSavedData(NbtCompound nbt) {
        GrugSavedNumbersNbt.write(this, nbt);
        GrugSavedListsNbt.write(this, nbt);
        GrugPowerNbt.write(this, nbt);
    }

    /** Applies what {@link #writeSavedData} wrote to this block entity. */
    public void readSavedData(NbtCompound nbt) {
        GrugSavedNumbersNbt.read(this, nbt);
        GrugSavedListsNbt.read(this, nbt);
        GrugPowerNbt.read(this, nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        GrugSavedNumbersNbt.read(this, nbt);
        GrugSavedListsNbt.read(this, nbt);
        GrugPowerNbt.read(this, nbt);

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
        GrugSavedNumbersNbt.write(this, nbt);
        GrugSavedListsNbt.write(this, nbt);
        GrugPowerNbt.write(this, nbt);
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
