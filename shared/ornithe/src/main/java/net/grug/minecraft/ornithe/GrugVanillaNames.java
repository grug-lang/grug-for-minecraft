package net.grug.minecraft.ornithe;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugFieldNames;

import java.lang.reflect.Field;

/**
 * The mapped name of a static field of {@code Block} or {@code Item}, which is the only name a
 * vanilla block or item has on Alpha and Beta.
 *
 * <p>A development run sees the mapped names themselves and an installed game sees intermediary
 * names, so both look the field up in {@code grug_vanilla_names.txt}, which the build writes from
 * this loader's mappings (gradle/ornithe-vanilla-names.gradle), under the namespace the game runs
 * in. See {@link GrugFieldNames}.
 */
public final class GrugVanillaNames {

    private static final String TABLE = "grug_vanilla_names.txt";

    private static GrugFieldNames names;

    private GrugVanillaNames() {}

    public static String of(Field field) {
        if (names == null) {
            names =
                    GrugFieldNames.read(
                            TABLE,
                            GrugVanillaNames.class.getResourceAsStream("/" + TABLE),
                            FabricLoader.getInstance()
                                    .getMappingResolver()
                                    .getCurrentRuntimeNamespace());
        }
        return names.mapped(field.getName());
    }
}
