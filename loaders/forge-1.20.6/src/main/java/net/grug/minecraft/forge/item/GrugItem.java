package net.grug.minecraft.forge.item;

import net.minecraft.world.item.Item;

public class GrugItem extends Item {
    public final long itemFileId;

    public GrugItem(Properties properties, long itemFileId) {
        super(properties);
        this.itemFileId = itemFileId;
    }
}
