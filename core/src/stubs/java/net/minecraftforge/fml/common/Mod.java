package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stand-in for the loader's own {@code @Mod}, so core can carry the annotation without
 * depending on the loader. Only the descriptor, the value (Forge 1.13 on) and the mod id (1.8 to
 * 1.12.2, which have no value) end up in GrugForgeMod's class file, which is all the loader's
 * scanner reads; this class is never packaged.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();

    String modid();
}
