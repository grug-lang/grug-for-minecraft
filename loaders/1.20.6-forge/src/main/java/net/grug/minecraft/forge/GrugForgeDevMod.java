package net.grug.minecraft.forge;

import net.grug.minecraft.bootstrap.GrugForgeMod;
import net.minecraftforge.fml.common.Mod;

/**
 * The development run's {@code @Mod}, which starts this layer through core's bootstrap exactly as
 * the universal jar does. Forge scans only the mod's own classes for {@code @Mod}, and in a
 * development run core is a library beside the mod, so its {@link GrugForgeMod} is not found there.
 * layerJar leaves this class out: in the universal jar, core's class sits in the same jar and is
 * the only one.
 */
@Mod(GrugForgeMod.MOD_ID)
public class GrugForgeDevMod extends GrugForgeMod {}
