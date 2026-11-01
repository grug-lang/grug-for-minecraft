package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.GrugSavedLists;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A grug block entity's saved lists in its NBT, as a list of named lists of records, the same
 * layout the other loaders write.
 *
 * <p>A list of name-tagged lists rather than one compound keyed by name, because Alpha's compound
 * cannot list its own keys, and both Ornithe versions share this. Each record holds its numbers as
 * a list of name and value pairs for the same reason, and its item as the version's own stack NBT,
 * so a record reads back with the same item its inventory slot would.
 */
public final class GrugSavedListsNbt {
    private static final String KEY = "GrugLists";

    private GrugSavedListsNbt() {}

    public static void write(Object blockEntity, NbtCompound nbt) {
        NbtList lists = new NbtList();
        for (Map.Entry<String, List<GrugSavedLists.Record>> entry :
                GrugSavedLists.snapshot(blockEntity).entrySet()) {
            NbtCompound list = new NbtCompound();
            list.putString("Name", entry.getKey());
            NbtList records = new NbtList();
            for (GrugSavedLists.Record record : entry.getValue()) {
                NbtCompound recordTag = new NbtCompound();
                NbtList numbers = new NbtList();
                for (Map.Entry<String, Double> number : record.numbers().entrySet()) {
                    NbtCompound numberTag = new NbtCompound();
                    numberTag.putString("Name", number.getKey());
                    numberTag.putDouble("Value", number.getValue());
                    numbers.addElement(numberTag);
                }
                recordTag.put("Numbers", numbers);
                if (record.item() != null) {
                    ItemStack stack =
                            (ItemStack) GrugCore.getAdapter().createItemStack(record.item(), 0, 1);
                    recordTag.put("Item", stack.writeNbt(new NbtCompound()));
                }
                records.addElement(recordTag);
            }
            list.put("Records", records);
            lists.addElement(list);
        }
        nbt.put(KEY, lists);
    }

    public static void read(Object blockEntity, NbtCompound nbt) {
        Map<String, List<GrugSavedLists.Record>> lists = new HashMap<>();
        NbtList listTags = nbt.getList(KEY);
        for (int i = 0; i < listTags.size(); i++) {
            NbtCompound listTag = (NbtCompound) listTags.get(i);
            List<GrugSavedLists.Record> records = new ArrayList<>();
            NbtList recordTags = listTag.getList("Records");
            for (int j = 0; j < recordTags.size(); j++) {
                NbtCompound recordTag = (NbtCompound) recordTags.get(j);
                Map<String, Double> numbers = new HashMap<>();
                NbtList numberTags = recordTag.getList("Numbers");
                for (int k = 0; k < numberTags.size(); k++) {
                    NbtCompound numberTag = (NbtCompound) numberTags.get(k);
                    numbers.put(numberTag.getString("Name"), numberTag.getDouble("Value"));
                }
                Object item = null;
                if (recordTag.contains("Item")) {
                    item =
                            GrugCore.getAdapter()
                                    .getStackItem(new ItemStack(recordTag.getCompound("Item")));
                }
                records.add(new GrugSavedLists.Record(numbers, item));
            }
            lists.put(listTag.getString("Name"), records);
        }
        GrugSavedLists.replace(blockEntity, lists);
    }
}
