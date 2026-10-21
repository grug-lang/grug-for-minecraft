package cpw.mods.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stand-in for FML 1.7.10's own {@code @Mod}, so core can carry the annotation without
 * depending on the loader. Only the descriptor and the mod id end up in GrugForgeMod's class file,
 * which is all the loader's scanner reads; this class is never packaged.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String modid();
}
