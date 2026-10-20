package net.grug.minecraft.bootstrap.hidden;

/** An FMLLoader that GrugForgeLayers may not call, since the class is not public. */
class HiddenFmlLoader {
    public static Object versionInfo() {
        return null;
    }
}
