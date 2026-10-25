package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.forge125.block.GrugBlocks;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.minecraft.src.ItemStack;
import net.minecraft.src.NBTBase;
import net.minecraft.src.NBTTagCompound;
import net.minecraft.src.NBTTagList;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;

/** 1.2.5's NBT, for {@link GrugBlockEntityNbt}'s test-only edits. */
public final class GrugBlockEntityNbtAccess implements GrugBlockEntityNbt.Access {
    @Override
    public Object write(Object blockEntity) {
        // TileEntity.writeToNBT looks the class up in the mapping ModLoader fills on registration.
        GrugBlocks.init();

        NBTTagCompound nbt = new NBTTagCompound();
        ((TileEntity) blockEntity).writeToNBT(nbt);
        return nbt;
    }

    /**
     * As a chunk unload and load: the old tile is removed, and the new one is created from the NBT
     * and then set in the world, which validates it. The block is then drawn again, as a loaded
     * chunk is, because a renderer only knows the block entities its section held when it was last
     * built.
     */
    @Override
    public void replace(Object blockEntity, Object compound) {
        TileEntity old = (TileEntity) blockEntity;
        World world = old.worldObj;
        world.removeBlockTileEntity(old.xCoord, old.yCoord, old.zCoord);
        TileEntity loaded = TileEntity.createAndLoadEntity((NBTTagCompound) compound);
        world.setBlockTileEntity(old.xCoord, old.yCoord, old.zCoord, loaded);
        GrugCore.getAdapter().redraw(loaded);
    }

    @Override
    public Object newCompound() {
        return new NBTTagCompound();
    }

    @Override
    public Object newList() {
        return new NBTTagList();
    }

    @Override
    public boolean contains(Object compound, String key) {
        return ((NBTTagCompound) compound).hasKey(key);
    }

    @Override
    public Object getCompound(Object compound, String key) {
        return ((NBTTagCompound) compound).getCompoundTag(key);
    }

    @Override
    public Object getList(Object compound, String key) {
        return ((NBTTagCompound) compound).getTagList(key);
    }

    @Override
    public void put(Object compound, String key, Object element) {
        ((NBTTagCompound) compound).setTag(key, (NBTBase) element);
    }

    @Override
    public void putNumber(Object compound, String key, String type, double value) {
        NBTTagCompound nbt = (NBTTagCompound) compound;
        switch (type) {
            case "byte":
                nbt.setByte(key, (byte) value);
                break;
            case "short":
                nbt.setShort(key, (short) value);
                break;
            case "int":
                nbt.setInteger(key, (int) value);
                break;
            case "long":
                nbt.setLong(key, (long) value);
                break;
            case "float":
                nbt.setFloat(key, (float) value);
                break;
            default:
                nbt.setDouble(key, value);
                break;
        }
    }

    @Override
    public void putItem(Object compound, String key, Object item) {
        ItemStack stack = (ItemStack) GrugCore.getAdapter().createItemStack(item, 0);
        ((NBTTagCompound) compound).setTag(key, stack.writeToNBT(new NBTTagCompound()));
    }

    @Override
    public int size(Object list) {
        return ((NBTTagList) list).tagCount();
    }

    @Override
    public Object getCompound(Object list, int index) {
        return (NBTTagCompound) ((NBTTagList) list).tagAt(index);
    }

    @Override
    public void add(Object list, Object element) {
        ((NBTTagList) list).appendTag((NBTBase) element);
    }
}
