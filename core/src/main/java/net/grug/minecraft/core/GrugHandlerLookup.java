package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;

import java.lang.reflect.Field;
import java.util.function.Function;

/**
 * Finds the connection and the player a server-side network handler belongs to, by type, so a
 * loader whose game names differ between versions does not depend on field names (the Beta and
 * StationAPI control channels, #1040, #1607). The loaders pass their own game classes, so the walks
 * are unit-tested here with plain fixtures instead of a running server.
 */
public final class GrugHandlerLookup {
    private GrugHandlerLookup() {}

    /**
     * The value of the first field of {@code holder} (or of a class it extends, up to the JDK's
     * own) whose type is exactly {@code type}, which may be null; else, while {@code depth} is
     * above 0, the first non-null result of the same walk into every field whose type is named
     * {@code wrapperName}, one level shallower; else null.
     */
    public static <T> T connectionOf(Object holder, Class<T> type, String wrapperName, int depth) {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); !isJdk(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == type) return type.cast(read(field, holder));
                if (depth > 0 && field.getType().getName().equals(wrapperName)) {
                    T nested = connectionOf(read(field, holder), type, wrapperName, depth - 1);
                    if (nested != null) return nested;
                }
            }
        }
        return null;
    }

    /**
     * The player a handler belongs to: the first non-null field of the handler (or of a superclass,
     * up to the JDK's own) that holds a {@code playerType}, else the first of {@code candidates}
     * that holds the handler in any field, else null.
     */
    public static <P> P playerOf(Object handler, Class<P> playerType, Iterable<?> candidates) {
        for (Class<?> c = handler.getClass(); !isJdk(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!playerType.isAssignableFrom(field.getType())) continue;
                Object value = read(field, handler);
                if (value != null) return playerType.cast(value);
            }
        }
        for (Object candidate : candidates) {
            for (Class<?> c = candidate.getClass(); !isJdk(c); c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (read(field, candidate) == handler) return playerType.cast(candidate);
                }
            }
        }
        return null;
    }

    /**
     * The first non-null {@code connectionIn} answers for the value of a field of {@code holder}
     * (or of a class it extends, up to the JDK's own) whose type is {@code handlerType} or a
     * subtype of it, else null: the connection a player's own network handler holds (#1098).
     */
    public static <T> T nestedConnectionOf(
            Object holder, Class<?> handlerType, Function<Object, T> connectionIn) {
        for (Class<?> c = holder.getClass(); !isJdk(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!handlerType.isAssignableFrom(field.getType())) continue;
                T connection = connectionIn.apply(read(field, holder));
                if (connection != null) return connection;
            }
        }
        return null;
    }

    private static boolean isJdk(Class<?> type) {
        return type.getName().startsWith("java.");
    }

    @GrugGenerated(
            "the IllegalAccessException arm: Field.get cannot throw it once setAccessible(true) has"
                    + " succeeded, and a failing setAccessible throws a different exception")
    private static Object read(Field field, Object holder) {
        try {
            field.setAccessible(true);
            return field.get(holder);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + field, e);
        }
    }
}
