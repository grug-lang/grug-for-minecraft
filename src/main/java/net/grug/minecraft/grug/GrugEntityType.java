package net.grug.minecraft.grug;

public enum GrugEntityType {
    Block, BlockEntity, BlockPos, Entity, Item, ItemEntity, ItemStack, Level, ResourceLocation, Vec3;

    private final static GrugEntityType[] values = GrugEntityType.values();

    public static GrugEntityType get(int i) {
        return values[i];
    }
}
