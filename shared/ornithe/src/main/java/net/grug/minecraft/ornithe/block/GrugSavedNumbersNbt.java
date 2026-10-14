package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.grug.GrugSavedNumbers;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.HashMap;
import java.util.Map;

/**
 * A grug block entity's saved numbers in its NBT, as a list of name and value pairs.
 *
 * <p>A list rather than one compound keyed by name, because Alpha's compound cannot list its own
 * keys, and both Ornithe versions share this.
 */
public final class GrugSavedNumbersNbt {
    private static final String KEY = "GrugNumbers";

    private GrugSavedNumbersNbt() {}

    public static void write(Object blockEntity, NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Map.Entry<String, Double> entry : GrugSavedNumbers.snapshot(blockEntity).entrySet()) {
            NbtCompound number = new NbtCompound();
            number.putString("Name", entry.getKey());
            number.putDouble("Value", entry.getValue());
            list.addElement(number);
        }
        nbt.put(KEY, list);
    }

    public static void read(Object blockEntity, NbtCompound nbt) {
        Map<String, Double> numbers = new HashMap<>();
        NbtList list = nbt.getList(KEY);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound number = (NbtCompound) list.get(i);
            numbers.put(number.getString("Name"), number.getDouble("Value"));
        }
        GrugSavedNumbers.replace(blockEntity, numbers);
    }
}
