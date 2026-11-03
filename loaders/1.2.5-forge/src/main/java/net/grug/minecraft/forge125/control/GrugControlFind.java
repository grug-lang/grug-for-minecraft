package net.grug.minecraft.forge125.control;

import net.grug.minecraft.grug.GrugGenerated;

import java.lang.reflect.Field;

/**
 * Finds a field's value by its type, so the control channel does not depend on the game's field
 * names, which are obfuscated at run time.
 */
@GrugGenerated(
        "the 1.2.5 channel glue of #1040: no test the coverage gate counts can reach it, since only"
                + " the multiplayer job runs it")
public final class GrugControlFind {
    private GrugControlFind() {}

    /**
     * The first field of {@code holder} (or of a class it extends, up to the JDK's own) whose type
     * is {@code type}, read; null when there is none or it holds null.
     */
    public static <T> T byType(Object holder, Class<T> type) {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); c != null && !c.getName().startsWith("java."); ) {
            for (Field field : c.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value != null) return type.cast(value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "cannot read " + field + " to find a " + type.getName(), e);
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
