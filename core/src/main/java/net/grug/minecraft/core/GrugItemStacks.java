package net.grug.minecraft.core;

import java.util.function.ToDoubleFunction;

/**
 * Reads of an item stack the game may have left null, and the rule for how many items a stack may
 * hold.
 *
 * <p>1.2.5's {@code EntityItem.readEntityFromNBT} sets its stack to {@code
 * ItemStack.loadItemStackFromNBT}'s answer, which is null for an item id the save names and the
 * game does not have, and then kills the entity. Asking a dead item entity for a field of its stack
 * has to answer 0 rather than throw, so the null arm is read here, where a unit test covers both
 * sides, instead of a loader carrying a ternary whose null arm no run reaches.
 *
 * <p>The count rule is here too, shared by the host function that makes a stack and the recipe
 * parser that checks a recipe's result before registering it, so both refuse the same counts with
 * the same reason.
 */
public final class GrugItemStacks {
    private GrugItemStacks() {}

    /**
     * A number a stack carries, such as its damage or its count, or 0 when the game left the stack
     * null.
     *
     * @param stack the stack to read, or null
     * @param read reads the number from a stack that is not null
     */
    public static double numberOrZero(Object stack, ToDoubleFunction<Object> read) {
        return stack == null ? 0.0 : read.applyAsDouble(stack);
    }

    /**
     * Whether a count can be made into an item stack the loader's save keeps.
     *
     * <p>A stack holds at least one item, and a fraction names no stack. {@code max} is the largest
     * count the loader's own save keeps: 1.2.5, Alpha and both b1.7.3 loaders write the count as
     * one NBT byte, so a stack beyond 127 comes back as a different count on the next load, while
     * 1.20.6 writes an int.
     */
    public static boolean countFits(double count, int max) {
        return count >= 1 && count <= max && count == Math.floor(count);
    }

    /**
     * Why a count cannot be made into an item stack, or null when it can.
     *
     * <p>The message names the loader's own largest count, so a mod author sees the limit that
     * applies where the mod runs.
     */
    public static String countProblem(double count, int max) {
        if (countFits(count, max)) {
            return null;
        }
        return "The count has to be a whole number from 1 to " + max + ", and was " + count + ".";
    }
}
