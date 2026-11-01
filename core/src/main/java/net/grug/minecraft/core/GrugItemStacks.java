package net.grug.minecraft.core;

import java.util.function.ToDoubleFunction;

/**
 * Reads of an item stack the game may have left null.
 *
 * <p>1.2.5's {@code EntityItem.readEntityFromNBT} sets its stack to {@code
 * ItemStack.loadItemStackFromNBT}'s answer, which is null for an item id the save names and the
 * game does not have, and then kills the entity. Asking a dead item entity for its damage has to
 * answer 0 rather than throw, so the null arm is read here, where a unit test covers both sides,
 * instead of a loader carrying a ternary whose null arm no run reaches.
 */
public final class GrugItemStacks {
    private GrugItemStacks() {}

    /**
     * The damage a stack carries, or 0 when the game left the stack null.
     *
     * @param stack the stack to read, or null
     * @param damage reads the damage of a stack that is not null
     */
    public static double damageOrZero(Object stack, ToDoubleFunction<Object> damage) {
        return stack == null ? 0.0 : damage.applyAsDouble(stack);
    }
}
