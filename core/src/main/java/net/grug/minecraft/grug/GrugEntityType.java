package net.grug.minecraft.grug;

public enum GrugEntityType {
    AddResponse,
    Block,
    BlockEntity,
    BlockEntityInitContext,
    BlockInitContext,
    BlockPos,
    BlockUseContext,
    Color,
    Entity,
    ExtractResponse,
    GUI,
    Item,
    ItemEntity,
    ItemStack,
    Level,
    Option,
    OverlayClickContext,
    OverlayKeyContext,
    OverlayRenderContext,
    OverlayScrollContext,
    Player,
    RenderContext,
    ResourceLocation,
    Screenshot,
    ShapeContext,
    TestInitContext,
    TestTickContext,
    Vec3;

    private static final GrugEntityType[] values = GrugEntityType.values();

    public static GrugEntityType get(int i) {
        return values[i];
    }
}
