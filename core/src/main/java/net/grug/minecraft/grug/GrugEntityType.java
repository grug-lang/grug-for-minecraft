package net.grug.minecraft.grug;

public enum GrugEntityType {
    Block,
    BlockEntity,
    BlockPos,
    Color,
    Entity,
    GUI,
    Item,
    ItemEntity,
    ItemStack,
    Level,
    Option,
    Player,
    ResourceLocation,
    Screenshot,
    Vec3;

    private static final GrugEntityType[] values = GrugEntityType.values();

    public static GrugEntityType get(int i) {
        return values[i];
    }
}
