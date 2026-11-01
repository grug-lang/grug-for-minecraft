package net.grug.minecraft.grug;

public class GrugItemData {
    public final String id;

    /**
     * The damage points a glider item has before it breaks, or 0 when the item is not a glider. Set
     * by {@code ItemInitContext.set_glider}.
     */
    public int gliderMaxDamage = 0;

    /**
     * Whether the item is a firework rocket, set by {@code ItemInitContext.set_firework_rocket}.
     */
    public boolean fireworkRocket = false;

    public GrugItemData(String id) {
        this.id = id;
    }
}
