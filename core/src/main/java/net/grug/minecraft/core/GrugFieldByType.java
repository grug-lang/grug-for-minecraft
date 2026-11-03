package net.grug.minecraft.core;

import java.lang.reflect.Field;

/**
 * Finds a field's value by its type, so a loader whose game names are obfuscated at run time does
 * not depend on them (the 1.2.5 control channel, #1040).
 */
public final class GrugFieldByType {
    private GrugFieldByType() {}

    /**
     * The first field of {@code holder} (or of a class it extends, up to the JDK's own) whose type
     * is {@code type}, read; null when there is none or it holds null.
     */
    public static <T> T find(Object holder, Class<T> type) throws IllegalAccessException {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); !c.getName().startsWith("java."); ) {
            for (Field field : c.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(holder);
                if (value != null) return type.cast(value);
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
