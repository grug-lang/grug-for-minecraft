package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.grug.GrugSavedStrings;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.HashMap;
import java.util.Map;

/**
 * A grug block entity's saved text in its NBT, as a list of name and value pairs.
 *
 * <p>A list rather than one compound keyed by name, because Alpha's compound cannot list its own
 * keys, and both Ornithe versions share this. An empty string is written and read as itself, so it
 * is told apart from a name that was never saved.
 */
public final class GrugSavedStringsNbt {
    private static final String KEY = "GrugStrings";

    private GrugSavedStringsNbt() {}

    public static void write(Object blockEntity, NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Map.Entry<String, String> entry : GrugSavedStrings.snapshot(blockEntity).entrySet()) {
            NbtCompound string = new NbtCompound();
            string.putString("Name", entry.getKey());
            string.putString("Value", entry.getValue());
            list.addElement(string);
        }
        nbt.put(KEY, list);
    }

    public static void read(Object blockEntity, NbtCompound nbt) {
        Map<String, String> strings = new HashMap<>();
        NbtList list = nbt.getList(KEY);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound string = (NbtCompound) list.get(i);
            strings.put(string.getString("Name"), string.getString("Value"));
        }
        GrugSavedStrings.replace(blockEntity, strings);
    }
}
