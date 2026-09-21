package net.grug.minecraft.ornithe.item;

import net.minecraft.item.Item;

public class GrugItem extends Item {
    public final long itemFileId;

    public GrugItem(int id, long itemFileId) {
        super(id);
        this.itemFileId = itemFileId;
    }
}
