package net.grug.minecraft.grug;

import net.modificationstation.stationapi.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public class GrugItemData {
    public final Identifier id;
    public String itemModelPath;
    public final List<String> langPaths = new ArrayList<>();

    public GrugItemData(Identifier id) {
        this.id = id;
    }
}
