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
    Config,
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
    OverlayWorldBindingContext,
    OverlayWorldContext,
    OverlayWorldKeyContext,
    Player,
    RenderContext,
    ResourceLocation,
    Screenshot,
    ShapeContext,
    SpawnSquares,
    TestInitContext,
    TestTickContext,
    Vec3,
    Screen,
    Widget,
    ScreenInitContext,
    ScreenWidgetContext,
    ListRowResponse,
    Random;

    private static final GrugEntityType[] values = GrugEntityType.values();

    public static GrugEntityType get(int i) {
        return values[i];
    }
}
