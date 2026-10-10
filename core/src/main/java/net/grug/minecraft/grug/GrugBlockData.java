package net.grug.minecraft.grug;

public class GrugBlockData {
    public final String id;
    public String blockEntityString;

    public float hardness = 0.0f;
    public String material = "stone";
    public int inventorySize = 0;

    /**
     * How much light the block emits, 0 to 15, declared by set_light_emission. A block at 15 is
     * what makes the coverage_render fixture's crop uniform, so a light update that leaves a stale
     * value behind has no other level to land on. See #262.
     */
    public int lightEmission = 0;

    /**
     * Whether the block draws its own geometry from its block entity's render function, declared by
     * set_custom_render. When it does, the loader skips the model its blockstate names.
     */
    public boolean customRender = false;

    public GrugBlockData(String id) {
        this.id = id;
    }
}
