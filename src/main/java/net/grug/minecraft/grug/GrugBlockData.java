package net.grug.minecraft.grug;

import net.modificationstation.stationapi.api.util.Identifier;

public class GrugBlockData {
    public final Identifier id;
    public String blockEntityString;

    public float hardness = 0.0f;
    public String material = "stone";
    public int inventorySize = 0;

    public GrugBlockData(Identifier id) {
        this.id = id;
    }
}
