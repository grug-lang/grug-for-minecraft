package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shapeless crafting for a game version whose {@code CraftingManager} has none.
 *
 * <p>Beta 1.7.3 and every later version register a shapeless recipe with the game. Alpha 1.1.2_01
 * has only the shaped registration, so a mod that ships a shapeless recipe gets nothing there.
 * Rather than let the recipe go missing, the Alpha loader mixes into {@code CraftingManager} and asks
 * this class first, which is the same thing the game does for its own recipes: a recipe matches when
 * the grid holds its ingredients and nothing else, whatever order they are in.
 *
 * <p>The comparison is on item ids, because that is all a legacy crafting grid carries: the id an
 * empty slot holds, and the id of the item in each filled one. Stack sizes and metadata are the
 * recipe's business, and Alpha's own shaped recipes ignore them the same way.
 */
public final class GrugShapelessRecipes {

    /**
     * The id a legacy grid holds for a slot with nothing in it. Alpha's shaped recipes compare
     * against it for every cell the pattern does not cover, so it is what a blank slot reads as.
     */
    public static final int EMPTY_SLOT = -1;

    private static final List<int[]> inputs = new ArrayList<>();
    private static final List<Object> outputs = new ArrayList<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugShapelessRecipes() {}

    /** Adds a recipe that matches {@code ingredientIds} in any order and yields {@code output}. */
    public static void register(int[] ingredientIds, Object output) {
        inputs.add(Arrays.copyOf(ingredientIds, ingredientIds.length));
        outputs.add(output);
    }

    /** How many shapeless recipes are registered, which is what the tests read back. */
    public static int registered() {
        return inputs.size();
    }

    /**
     * The output of the first recipe {@code gridIds} matches, or null when none does.
     *
     * <p>The output is the recipe's own object rather than a copy, because copying it is the game's
     * job: {@code CraftingManager} hands its caller a fresh stack from {@code construct}, so the mixin
     * that asks here copies before returning.
     */
    public static Object match(int[] gridIds) {
        int[] grid = filled(gridIds);
        for (int i = 0; i < inputs.size(); i++) {
            if (holds(grid, inputs.get(i))) {
                return outputs.get(i);
            }
        }
        return null;
    }

    /** The grid's item ids with the empty slots dropped, which is what a shapeless recipe compares. */
    private static int[] filled(int[] gridIds) {
        int count = 0;
        for (int id : gridIds) {
            if (id != EMPTY_SLOT) count++;
        }
        int[] filled = new int[count];
        int at = 0;
        for (int id : gridIds) {
            if (id != EMPTY_SLOT) filled[at++] = id;
        }
        return filled;
    }

    /**
     * Whether the grid holds exactly the recipe's ingredients, in any order.
     *
     * <p>Each grid slot is consumed once, so two of one ingredient satisfy only a recipe that asks
     * for two.
     */
    private static boolean holds(int[] grid, int[] recipe) {
        if (grid.length != recipe.length) return false;

        int[] remaining = Arrays.copyOf(grid, grid.length);
        for (int wanted : recipe) {
            boolean found = false;
            for (int i = 0; i < remaining.length; i++) {
                if (remaining[i] == wanted) {
                    remaining[i] = EMPTY_SLOT;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
