package net.grug.minecraft.ornithe;

import java.lang.reflect.Method;

/**
 * A game method whose name differs between the jar this layer is compiled against and the jar it
 * runs on.
 *
 * <p>Ornithe maps the game to named methods for development and to the intermediary's names in what
 * it installs, and a call compiled against one is not remapped into the other at runtime, so a
 * lookup by one name works on only one of the two. Both names are tried, the development one first.
 * {@link GrugFieldAccess} does the same for fields (#1522).
 */
final class GrugMethodAccess {

    private GrugMethodAccess() {}

    /**
     * The method of {@code owner} that {@code developmentName} names with the given parameter
     * types, or else the one {@code intermediaryName} names. The missing name is the exception when
     * neither method exists.
     */
    static Method method(
            Class<?> owner,
            String developmentName,
            String intermediaryName,
            Class<?>... parameterTypes)
            throws NoSuchMethodException {
        try {
            return owner.getMethod(developmentName, parameterTypes);
        } catch (NoSuchMethodException missing) {
            return owner.getMethod(intermediaryName, parameterTypes);
        }
    }
}
