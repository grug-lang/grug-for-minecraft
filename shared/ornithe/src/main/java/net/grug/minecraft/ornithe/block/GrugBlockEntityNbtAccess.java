package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.World;

/** Both Ornithe versions' NBT, for {@link GrugBlockEntityNbt}'s test-only edits. */
public final class GrugBlockEntityNbtAccess implements GrugBlockEntityNbt.Access {
    @Override
    public Object write(Object blockEntity) {
        NbtCompound nbt = new NbtCompound();
        ((BlockEntity) blockEntity).writeNbt(nbt);
        return nbt;
    }

    /**
     * As a chunk unload and load: the old block entity is removed first, because setBlockEntity
     * does not mark the one it replaces removed, and would leave it ticking beside the new one. The
     * block is then drawn again, as a loaded chunk is, because a renderer only knows the block
     * entities its section held when it was last built.
     */
    @Override
    public void replace(Object blockEntity, Object compound) {
        BlockEntity old = (BlockEntity) blockEntity;
        World world = old.world;
        world.removeBlockEntity(old.x, old.y, old.z);
        BlockEntity loaded = BlockEntity.fromNbt((NbtCompound) compound);
        world.setBlockEntity(old.x, old.y, old.z, loaded);
        GrugCore.getAdapter().redraw(loaded);
    }

    @Override
    public Object newCompound() {
        return new NbtCompound();
    }

    @Override
    public Object newList() {
        return new NbtList();
    }

    @Override
    public boolean contains(Object compound, String key) {
        return ((NbtCompound) compound).contains(key);
    }

    @Override
    public Object getCompound(Object compound, String key) {
        return ((NbtCompound) compound).getCompound(key);
    }

    @Override
    public Object getList(Object compound, String key) {
        return ((NbtCompound) compound).getList(key);
    }

    @Override
    public void put(Object compound, String key, Object element) {
        ((NbtCompound) compound).put(key, (NbtElement) element);
    }

    @Override
    public void putNumber(Object compound, String key, String type, double value) {
        NbtCompound nbt = (NbtCompound) compound;
        switch (type) {
            case "byte":
                nbt.putByte(key, (byte) value);
                break;
            case "short":
                nbt.putShort(key, (short) value);
                break;
            case "int":
                nbt.putInt(key, (int) value);
                break;
            case "long":
                nbt.putLong(key, (long) value);
                break;
            case "float":
                nbt.putFloat(key, (float) value);
                break;
            default:
                nbt.putDouble(key, value);
                break;
        }
    }

    @Override
    public void putItem(Object compound, String key, Object item) {
        ItemStack stack = (ItemStack) GrugCore.getAdapter().createItemStack(item, 0, 1);
        ((NbtCompound) compound).put(key, stack.writeNbt(new NbtCompound()));
    }

    @Override
    public int size(Object list) {
        return ((NbtList) list).size();
    }

    @Override
    public Object getCompound(Object list, int index) {
        return (NbtCompound) ((NbtList) list).get(index);
    }

    @Override
    public void add(Object list, Object element) {
        ((NbtList) list).addElement((NbtElement) element);
    }
}
