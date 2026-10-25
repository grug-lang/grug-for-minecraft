package net.grug.minecraft.grug;

public enum GrugEntityType {
    AddResponse,
    Block,
    BlockEntity,
    BlockPos,
    Color,
    Entity,
    ExtractResponse,
    GUI,
    Item,
    ItemEntity,
    ItemStack,
    Level,
    Option,
    Player,
    RenderContext,
    ResourceLocation,
    Screenshot,
    TestRunContext,
    Vec3;

    private static final GrugEntityType[] values = GrugEntityType.values();

    public static GrugEntityType get(int i) {
        return values[i];
    }
}
