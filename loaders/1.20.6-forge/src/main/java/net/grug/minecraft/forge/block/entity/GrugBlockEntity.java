package net.grug.minecraft.forge.block.entity;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.forge.GrugModLoader;
import net.grug.minecraft.forge.block.GrugBakedModel;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugObject;
import net.grug.minecraft.grug.GrugPower;
import net.grug.minecraft.grug.GrugSavedLists;
import net.grug.minecraft.grug.GrugSavedNumbers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GrugBlockEntity extends BlockEntity
        implements Container, GrugSavedNumbers.Holder, GrugSavedLists.Holder, GrugPower.Holder {
    private long entityHandle = 0;
    private long tickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private long renderFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean renderFnIdResolved = false;
    private long frameRenderFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean frameRenderFnIdResolved = false;
    private boolean initStarted = false;

    /**
     * The host entities this block entity's member scope created, rooted here so they live exactly
     * as long as the block entity and are freed with it (the legacy GrugEntity.childEntities idea).
     */
    public final List<GrugObject> childEntities = new ArrayList<>();

    private NonNullList<ItemStack> stacks = NonNullList.create();
    private boolean sized = false;

    /**
     * The cached recipe result, in a one-slot container of its own so it never takes an inventory
     * address: {@link #getContainerSize()} reports only the grid. The adapter's recipe lookup fills
     * it, the GUI result slot renders it, and taking from either path removes from this same slot.
     */
    private final SimpleContainer resultContainer = new SimpleContainer(1);

    public GrugBlockEntity(BlockPos pos, BlockState state) {
        super(GrugModLoader.BLOCK_ENTITIES.getEntries().iterator().next().get(), pos, state);
    }

    private void ensureSized() {
        if (sized) return;
        sized = trySizeInventory();
    }

    /** The one-slot container holding the cached crafting result. */
    public Container getResultContainer() {
        return resultContainer;
    }

    /** Caches the recipe result, or clears it when {@code stack} is empty. */
    public void setResultStack(ItemStack stack) {
        resultContainer.setItem(0, stack);
    }

    /** Removes up to {@code amount} from the cached result and returns what was removed. */
    public ItemStack takeResultStack(int amount) {
        return resultContainer.removeItem(0, amount);
    }

    /** The cached result as it stands, or null when there is none. */
    public ItemStack getResultStack() {
        ItemStack stack = resultContainer.getItem(0);
        return !stack.isEmpty() ? stack : null;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        initGrug();
    }

    /**
     * The position and level the breaking overlay needs to draw this block's custom geometry.
     *
     * <p>The overlay is drawn outside a chunk compile, and it asks the block's baked model for its
     * quads with this block entity's model data instead of with the position the section mesher
     * supplies. Without them {@link GrugBakedModel} has no place to look the block entity up, so it
     * draws nothing and a custom-rendered block being mined shows no crack. These are the same two
     * values the mesher hands the model, and {@code onLoad} asks the game to refresh them, so the
     * overlay sees them from the first frame. See #201.
     */
    @Override
    public ModelData getModelData() {
        return ModelData.builder()
                .with(GrugBakedModel.POS, worldPosition)
                .with(GrugBakedModel.LEVEL, level)
                .build();
    }

    /**
     * Creates this block entity's grug entity.
     *
     * <p>Serialized because 1.20.6 compiles chunks on worker threads: the chunk mesher asks for the
     * handle while the render thread's frame renderer can ask for it at the same time, and two
     * unsynchronized calls would both find no handle, create one each, and leak the first. The
     * other loaders compile and draw on one thread, so only this one needs it.
     *
     * <p>The state lock is taken before this entity's monitor, which is a method of its own so the
     * order is the same as every host call's. A script export holds the state lock and can reach
     * {@code initGrug} through an uninitialized block entity, so taking the monitor first would let
     * a mesher holding the monitor wait for the state lock while the export waits for the monitor.
     */
    private void initGrug() {
        GrugEntities.runLocked(this::initGrugLocked);
    }

    private synchronized void initGrugLocked() {
        if (entityHandle != 0 || initStarted) return;

        initStarted = true;
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
            "inventory/entity setup guards: the level and block are not controllable in tests")
    private boolean trySizeInventory() {
        if (level == null) return false;

        Block block = getBlockState().getBlock();
        if (!(block instanceof GrugBlock grugBlock)) return false;

        stacks = NonNullList.withSize(grugBlock.getInventorySize(), ItemStack.EMPTY);
        return true;
    }

    @GrugGenerated(
            "inventory/entity setup guards: the level and block are not controllable in tests")
    private long createGrugEntity() {
        ensureSized();
        if (level == null) return 0;

        Block block = getBlockState().getBlock();
        if (!(block instanceof GrugBlock grugBlock)) return 0;

        long fileId = grugBlock.getEntityFileId();
        if (fileId == Grug.INVALID_GRUG_FILE_ID) return 0;

        return GrugEntities.createEntityWithOwner(fileId, childEntities, this);
    }

    public void tick() {
        if (entityHandle == 0) initGrug();

        if (entityHandle != 0 && tickFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            GrugEntities.callExportFnWithOwner(entityHandle, tickFnId, new ArrayList<>(), null);
        }
    }

    /**
     * This block entity's grug entity handle, or 0 when it has none yet.
     *
     * <p>A chunk can be compiled before the block entity has ticked, which is when its geometry is
     * drawn, so the render path inits rather than assuming a tick got there first. On this version
     * the render path is the chunk mesher, which runs on a worker thread, but every native entry
     * takes the state lock, so the init is safe from there.
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

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
            childEntities.clear();
        }
        // Reset with the handle, so a tile that is re-inited after a hot reload resolves its render
        // function again rather than answering from a cache the destroyed entity left behind.
        renderFnIdResolved = false;
        frameRenderFnIdResolved = false;
    }

    // --- Container Implementation ---

    @Override
    public int getContainerSize() {
        ensureSized();
        return stacks.size();
    }

    @Override
    public boolean isEmpty() {
        ensureSized();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        ensureSized();
        return slot >= 0 && slot < stacks.size() ? stacks.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ensureSized();
        ItemStack result = ContainerHelper.removeItem(stacks, slot, amount);
        if (!result.isEmpty()) {
            setChanged();
            initGrug();
            if (entityHandle != 0) {
                long fnId = Grug.getExportFnId("BlockEntity", "item_extracted");
                if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                    ExportFns.BlockEntity_item_extracted(entityHandle, slot, amount);
                }
            }
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ensureSized();
        return ContainerHelper.takeItem(stacks, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        ensureSized();
        if (slot >= 0 && slot < stacks.size()) {
            stacks.set(slot, stack);
            if (stack.getCount() > getMaxStackSize()) {
                stack.setCount(getMaxStackSize());
            }
            setChanged();
            initGrug();
            if (entityHandle != 0) {
                long fnId = Grug.getExportFnId("BlockEntity", "item_inserted");
                if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                    ExportFns.BlockEntity_item_inserted(entityHandle, slot, stack.getCount());
                }
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
    public boolean stillValid(Player player) {
        return withinReach(player);
    }

    @GrugGenerated("container reachability: the game only asks with the GUI open")
    private boolean withinReach(Player player) {
        if (this.level == null || this.level.getBlockEntity(this.worldPosition) != this)
            return false;
        return player.distanceToSqr(
                        this.worldPosition.getX() + 0.5D,
                        this.worldPosition.getY() + 0.5D,
                        this.worldPosition.getZ() + 0.5D)
                <= 64.0D;
    }

    @Override
    public void clearContent() {
        ensureSized();
        stacks.clear();
        resultContainer.clearContent();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readSavedNumbers(tag);
        readSavedLists(tag, registries);
        GrugPower.load(this, tag.getDouble("GrugEnergy"));
        if (tag.contains("GrugInvSize")) {
            stacks = NonNullList.withSize(tag.getInt("GrugInvSize"), ItemStack.EMPTY);
            sized = true;
        } else {
            ensureSized();
        }
        ContainerHelper.loadAllItems(tag, stacks, registries);

        resultContainer.setItem(
                0,
                tag.contains("GrugResult")
                        ? ItemStack.parseOptional(registries, tag.getCompound("GrugResult"))
                        : ItemStack.EMPTY);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeSavedNumbers(tag);
        writeSavedLists(tag, registries);
        tag.putDouble("GrugEnergy", GrugPower.stored(this));
        ensureSized();
        tag.putInt("GrugInvSize", stacks.size());
        ContainerHelper.saveAllItems(tag, stacks, registries);

        ItemStack resultStack = resultContainer.getItem(0);
        if (!resultStack.isEmpty()) {
            tag.put("GrugResult", resultStack.save(registries));
        }
    }

    /** A saved number changed, so the chunk has to be saved with it. */
    @Override
    public void markSavedNumbersChanged() {
        setChanged();
    }

    /** The energy this block entity holds changed, so the chunk has to be saved with it. */
    @Override
    public void markEnergyChanged() {
        setChanged();
    }

    /**
     * What the client's copy of this block entity is sent when its chunk is, and on every redraw:
     * the same data the game saves, because the saved numbers in it are what the client's render
     * function draws from.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /** The packet a redraw sends the client, carrying {@link #getUpdateTag}. */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * The client's copy reads the data the server sent, then has its block meshed again, since the
     * packet was sent because what the render function draws has changed. The block itself did not
     * change, so nothing else would mesh it again.
     */
    @Override
    public void onDataPacket(
            Connection connection,
            ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider registries) {
        super.onDataPacket(connection, packet, registries);
        if (level != null && level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 0);
        }
    }

    /** The saved numbers, as a list of name and value pairs, as the older loaders write them. */
    private void writeSavedNumbers(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Double> entry : GrugSavedNumbers.snapshot(this).entrySet()) {
            CompoundTag number = new CompoundTag();
            number.putString("Name", entry.getKey());
            number.putDouble("Value", entry.getValue());
            list.add(number);
        }
        tag.put("GrugNumbers", list);
    }

    private void readSavedNumbers(CompoundTag tag) {
        Map<String, Double> numbers = new HashMap<>();
        ListTag list = tag.getList("GrugNumbers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag number = list.getCompound(i);
            numbers.put(number.getString("Name"), number.getDouble("Value"));
        }
        GrugSavedNumbers.replace(this, numbers);
    }

    /**
     * The saved lists, as a list of named lists of records, as the older loaders write them:
     * numbers as name and value pairs, and an item as this version's own stack data.
     */
    private void writeSavedLists(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag lists = new ListTag();
        for (Map.Entry<String, List<GrugSavedLists.Record>> entry :
                GrugSavedLists.snapshot(this).entrySet()) {
            CompoundTag list = new CompoundTag();
            list.putString("Name", entry.getKey());
            ListTag records = new ListTag();
            for (GrugSavedLists.Record record : entry.getValue()) {
                CompoundTag recordTag = new CompoundTag();
                ListTag numbers = new ListTag();
                for (Map.Entry<String, Double> number : record.numbers().entrySet()) {
                    CompoundTag numberTag = new CompoundTag();
                    numberTag.putString("Name", number.getKey());
                    numberTag.putDouble("Value", number.getValue());
                    numbers.add(numberTag);
                }
                recordTag.put("Numbers", numbers);
                if (record.item() != null) {
                    ItemStack stack =
                            (ItemStack) GrugCore.getAdapter().createItemStack(record.item(), 0);
                    recordTag.put("Item", stack.save(registries));
                }
                records.add(recordTag);
            }
            list.put("Records", records);
            lists.add(list);
        }
        tag.put("GrugLists", lists);
    }

    private void readSavedLists(CompoundTag tag, HolderLookup.Provider registries) {
        Map<String, List<GrugSavedLists.Record>> lists = new HashMap<>();
        ListTag listTags = tag.getList("GrugLists", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTags.size(); i++) {
            CompoundTag listTag = listTags.getCompound(i);
            List<GrugSavedLists.Record> records = new ArrayList<>();
            ListTag recordTags = listTag.getList("Records", Tag.TAG_COMPOUND);
            for (int j = 0; j < recordTags.size(); j++) {
                CompoundTag recordTag = recordTags.getCompound(j);
                Map<String, Double> numbers = new HashMap<>();
                ListTag numberTags = recordTag.getList("Numbers", Tag.TAG_COMPOUND);
                for (int k = 0; k < numberTags.size(); k++) {
                    CompoundTag numberTag = numberTags.getCompound(k);
                    numbers.put(numberTag.getString("Name"), numberTag.getDouble("Value"));
                }
                Object item = null;
                if (recordTag.contains("Item")) {
                    ItemStack stack =
                            ItemStack.parseOptional(registries, recordTag.getCompound("Item"));
                    if (!stack.isEmpty()) {
                        item = GrugCore.getAdapter().getStackItem(stack);
                    }
                }
                records.add(new GrugSavedLists.Record(numbers, item));
            }
            lists.put(listTag.getString("Name"), records);
        }
        GrugSavedLists.replace(this, lists);
    }
}
