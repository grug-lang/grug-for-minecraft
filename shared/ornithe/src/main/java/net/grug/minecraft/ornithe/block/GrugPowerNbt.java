package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.grug.GrugPower;
import net.minecraft.nbt.NbtCompound;

/**
 * The energy a grug block entity holds in its NBT, under a key of its own, which both Ornithe
 * versions share. It is only written while there is some, so a block entity that never received any
 * saves nothing extra, and a missing key reads as none.
 */
public final class GrugPowerNbt {
    private static final String KEY = "GrugEnergy";

    private GrugPowerNbt() {}

    public static void write(Object blockEntity, NbtCompound nbt) {
        double stored = GrugPower.stored(blockEntity);
        if (stored > 0) {
            nbt.putDouble(KEY, stored);
        }
    }

    public static void read(Object blockEntity, NbtCompound nbt) {
        GrugPower.load(blockEntity, nbt.getDouble(KEY));
    }
}
