package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.grug.GrugPower;
import net.minecraft.nbt.NbtCompound;

/**
 * The energy a grug block entity holds in its NBT, under a key of its own, which both Ornithe
 * versions share. It is always written, 0 when there is none, so it overwrites whatever an NBT
 * compound handed to the writer held under the key, and a missing key, as an old save has, reads as
 * none.
 */
public final class GrugPowerNbt {
    private static final String KEY = "GrugEnergy";

    private GrugPowerNbt() {}

    public static void write(Object blockEntity, NbtCompound nbt) {
        nbt.putDouble(KEY, GrugPower.stored(blockEntity));
    }

    public static void read(Object blockEntity, NbtCompound nbt) {
        GrugPower.load(blockEntity, nbt.getDouble(KEY));
    }
}
