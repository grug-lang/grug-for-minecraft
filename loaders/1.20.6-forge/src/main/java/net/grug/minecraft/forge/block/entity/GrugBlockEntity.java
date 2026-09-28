package net.grug.minecraft.forge.block.entity;

import net.grug.minecraft.forge.GrugModLoader;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class GrugBlockEntity extends BlockEntity implements Container {
    private long entityHandle = 0;
    private long tickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean initAttempted = false;

    private NonNullList<ItemStack> stacks = NonNullList.create();
    private boolean sized = false;

    public GrugBlockEntity(BlockPos pos, BlockState state) {
        super(GrugModLoader.BLOCK_ENTITIES.getEntries().iterator().next().get(), pos, state);
    }

    private void ensureSized() {
        if (sized) return;
        sized = trySizeInventory();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        initGrug();
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

        Grug.currentlyInitializingBlockEntity = this;
        long handle = Grug.createEntity(fileId);
        Grug.currentlyInitializingBlockEntity = null;
        return handle;
    }

    public void tick() {
        if (entityHandle == 0) initGrug();

        if (entityHandle != 0 && tickFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            List<GrugObject> oldFnEntities = Grug.fnEntities;
            Grug.fnEntities = new ArrayList<>();
            Grug.callExportFn(entityHandle, tickFnId);
            Grug.fnEntities = oldFnEntities;
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
        }
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

    public void notifyOutputTaken(int slot, int amount) {
        initGrug();
        if (entityHandle != 0) {
            long fnId = Grug.getExportFnId("BlockEntity", "output_taken");
            if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                ExportFns.BlockEntity_output_taken(entityHandle, slot, amount);
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
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("GrugInvSize")) {
            stacks = NonNullList.withSize(tag.getInt("GrugInvSize"), ItemStack.EMPTY);
            sized = true;
        } else {
            ensureSized();
        }
        ContainerHelper.loadAllItems(tag, stacks, registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ensureSized();
        tag.putInt("GrugInvSize", stacks.size());
        ContainerHelper.saveAllItems(tag, stacks, registries);
    }
}
