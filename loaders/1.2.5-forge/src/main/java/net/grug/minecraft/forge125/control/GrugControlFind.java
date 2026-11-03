package net.grug.minecraft.forge125.control;

import net.grug.minecraft.core.GrugFieldByType;
import net.grug.minecraft.grug.GrugGenerated;

/**
 * The lookup of a field by its type for the 1.2.5 control channel, which cannot name the game's
 * obfuscated fields.
 */
@GrugGenerated("a wrapper of the tested lookup that makes its checked exception unchecked")
public final class GrugControlFind {
    private GrugControlFind() {}

    /** {@link GrugFieldByType#find}, with the reflection failure made unchecked. */
    public static <T> T byType(Object holder, Class<T> type) {
        try {
            return GrugFieldByType.find(holder, type);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot find a " + type.getName() + " in " + holder, e);
        }
    }
}
