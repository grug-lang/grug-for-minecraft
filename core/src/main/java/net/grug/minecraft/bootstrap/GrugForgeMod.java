package net.grug.minecraft.bootstrap;

/**
 * The only {@code @Mod} class in the universal jar, which every Forge and NeoForge that reads the
 * jar's mods.toml constructs: it starts the Forge layer for the running Minecraft version, and on
 * every other game it does nothing.
 *
 * <p>It carries Forge's annotation, which Forge 1.13 on and NeoForge 1.20.1 look for, and
 * NeoForge's, which NeoForge 20.2 on look for. A loader that finds a mod in mods.toml without its
 * {@code @Mod} class stops the launch, so both have to be here. Neither annotation class exists on
 * the other's loader, which the JVM ignores. It is Java 8 because Forge 1.13 to 1.16 scan classes
 * with an ASM that cannot read newer ones, and they run on Java 8.
 */
@net.minecraftforge.fml.common.Mod(GrugForgeMod.MOD_ID)
@net.neoforged.fml.common.Mod(GrugForgeMod.MOD_ID)
public class GrugForgeMod {
    /** The mod id in the root mods.toml and neoforge.mods.toml that universal_jar.py writes. */
    public static final String MOD_ID = "grug";

    public GrugForgeMod() {
        GrugForgeLayers.load().start(getClass().getClassLoader());
    }
}
