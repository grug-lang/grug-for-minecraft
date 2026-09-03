package net.grug.minecraft.stationapi.item;

import net.modificationstation.stationapi.api.template.item.TemplateItem;
import net.modificationstation.stationapi.api.util.Identifier;

public class GrugItem extends TemplateItem {
    public final Identifier identifier;
    public long itemFileId;

    public GrugItem(Identifier identifier, long itemFileId) {
        super(identifier);
        this.identifier = identifier;
        this.itemFileId = itemFileId;
    }
}
