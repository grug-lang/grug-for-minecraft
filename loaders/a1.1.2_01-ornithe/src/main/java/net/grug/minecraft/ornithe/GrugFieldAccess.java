package net.grug.minecraft.ornithe;

import java.lang.reflect.Field;

/**
 * A game field whose name differs between the jar this layer is compiled against and the jar it
 * runs on.
 *
 * <p>Ornithe maps the game to named fields for development and to intermediary fields in what it
 * installs, and a string constant is not remapped with the code around it, so a lookup by one name
 * works on only one of the two. Both names are tried, the development one first. 1.2.5-forge does
 * the same for the fields of a container screen in its {@code GrugGuiAccess} (#1486).
 */
final class GrugFieldAccess {

    private GrugFieldAccess() {}

    /**
     * The field of {@code owner} that {@code developmentName} names, or else the one {@code
     * intermediaryName} names, made accessible. The missing name is the exception when neither
     * field exists.
     */
    static Field field(Class<?> owner, String developmentName, String intermediaryName)
            throws NoSuchFieldException {
        Field field;
        try {
            field = owner.getDeclaredField(developmentName);
        } catch (NoSuchFieldException missing) {
            field = owner.getDeclaredField(intermediaryName);
        }
        field.setAccessible(true);
        return field;
    }
}
