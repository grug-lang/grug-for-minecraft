package net.grug.minecraft.grug;

public class GrugBlockData {
    public final String id;
    public String blockEntityString;

    public float hardness = 0.0f;
    public String material = "stone";
    public int inventorySize = 0;

    public GrugBlockData(String id) {
        this.id = id;
    }
}
