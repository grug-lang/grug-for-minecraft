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
    DisplayTickContext,
    Entity,
    ExtractResponse,
    File,
    GUI,
    Item,
    ItemEntity,
    ItemStack,
    Level,
    LiquidResponse,
    Option,
    OverlayClickContext,
    OverlayHudContext,
    OverlayKeyContext,
    OverlayRenderContext,
    OverlayScrollContext,
    OverlayWorldContext,
    OverlayWorldKeyContext,
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
