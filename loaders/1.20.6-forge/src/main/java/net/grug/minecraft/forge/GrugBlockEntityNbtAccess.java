package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 1.20.6's NBT, for {@link GrugBlockEntityNbt}'s test-only edits.
 *
 * <p>The typed getters answer an empty tag for a key or index of another type, so a compound or
 * list is cast from the raw tag instead, which throws as the walk expects. A stack is saved with
 * the registries of the level whose block entity was last written, which is the one being edited.
 */
public final class GrugBlockEntityNbtAccess implements GrugBlockEntityNbt.Access {
    private HolderLookup.Provider registries;

    @Override
    public Object write(Object blockEntity) {
        BlockEntity be = (BlockEntity) blockEntity;
        registries = be.getLevel().registryAccess();
        return be.saveWithId(registries);
    }

    /**
     * As a chunk load: the new block entity is created from the NBT through loadStatic, then set in
     * the level, which marks the one it replaces removed. The block is then drawn again, as a
     * loaded chunk is, because a renderer only knows the block entities its section held when it
     * was last built.
     */
    @Override
    public void replace(Object blockEntity, Object compound) {
        BlockEntity old = (BlockEntity) blockEntity;
        Level level = old.getLevel();
        level.removeBlockEntity(old.getBlockPos());
        BlockEntity loaded =
                BlockEntity.loadStatic(
                        old.getBlockPos(), old.getBlockState(), (CompoundTag) compound, registries);
        level.setBlockEntity(loaded);
        GrugCore.getAdapter().redraw(loaded);
    }

    @Override
    public Object newCompound() {
        return new CompoundTag();
    }

    @Override
    public Object newList() {
        return new ListTag();
    }

    @Override
    public boolean contains(Object compound, String key) {
        return ((CompoundTag) compound).contains(key);
    }

    @Override
    public Object getCompound(Object compound, String key) {
        return (CompoundTag) ((CompoundTag) compound).get(key);
    }

    @Override
    public Object getList(Object compound, String key) {
        return (ListTag) ((CompoundTag) compound).get(key);
    }

    @Override
    public void put(Object compound, String key, Object element) {
        ((CompoundTag) compound).put(key, (Tag) element);
    }

    @Override
    public void putNumber(Object compound, String key, String type, double value) {
        CompoundTag tag = (CompoundTag) compound;
        switch (type) {
            case "byte" -> tag.putByte(key, (byte) value);
            case "short" -> tag.putShort(key, (short) value);
            case "int" -> tag.putInt(key, (int) value);
            case "long" -> tag.putLong(key, (long) value);
            case "float" -> tag.putFloat(key, (float) value);
            default -> tag.putDouble(key, value);
        }
    }

    @Override
    public void putItem(Object compound, String key, Object item) {
        ItemStack stack = (ItemStack) GrugCore.getAdapter().createItemStack(item, 0);
        ((CompoundTag) compound).put(key, stack.save(registries));
    }

    @Override
    public int size(Object list) {
        return ((ListTag) list).size();
    }

    @Override
    public Object getCompound(Object list, int index) {
        return (CompoundTag) ((ListTag) list).get(index);
    }

    @Override
    public void add(Object list, Object element) {
        ((ListTag) list).add((Tag) element);
    }
}
