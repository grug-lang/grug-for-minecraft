package net.grug.minecraft.forge125.item;

import net.minecraft.src.Item;

/**
 * A dynamic item backed by a grug item script.
 *
 * <p>1.2.5 has no item registry object, so a {@code GrugItem} is identified by the grug file id it
 * was built from. The adapter scans {@code Item.itemsList} for these when resolving a grug item.
 */
public class GrugItem extends Item {
    public final long itemFileId;

    public GrugItem(int id, long itemFileId) {
        super(id);
        this.itemFileId = itemFileId;
    }
}
