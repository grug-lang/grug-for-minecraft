package net.grug.minecraft.grug;

public enum GrugEntityType {
    Block, BlockEntity, BlockPos, Entity, GUI, Item, ItemEntity, ItemStack, Level, Player, ResourceLocation, Vec3;

    private final static GrugEntityType[] values = GrugEntityType.values();

    public static GrugEntityType get(int i) {
        return values[i];
    }
}
