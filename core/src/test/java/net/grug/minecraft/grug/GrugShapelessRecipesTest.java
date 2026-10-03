package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Covers the shapeless matcher a game version with no shapeless recipes needs.
 *
 * <p>The registry is static, as it has to be for the mixin that reads it, so each test works on item
 * ids of its own: a match returns the first recipe that fits, and a recipe an earlier test left
 * behind must not be the one answering.
 */
class GrugShapelessRecipesTest {

    private static int nextId = 1000;

    /** Item ids and an output no other test uses, so nothing already registered can answer first. */
    private static final class Recipe {
        private final int first = nextId++;
        private final int second = nextId++;
        private final Object output = new Object();

        void register() {
            GrugShapelessRecipes.register(new int[] {first, second}, output);
        }
    }

    /** A grid holding exactly the ids given, which is what a crafting table passes in. */
    private static int[] grid(int... ids) {
        int[] full = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            full[i] = ids[i];
        }
        return full;
    }

    @Test
    void matchesInAnyOrder() {
        Recipe recipe = new Recipe();
        recipe.register();

        assertSame(
                recipe.output,
                GrugShapelessRecipes.match(
                        grid(recipe.second, GrugShapelessRecipes.EMPTY_SLOT, recipe.first)));
    }

    @Test
    void ignoresWhereTheEmptySlotsAre() {
        Recipe recipe = new Recipe();
        recipe.register();

        assertSame(
                recipe.output,
                GrugShapelessRecipes.match(
                        grid(
                                GrugShapelessRecipes.EMPTY_SLOT,
                                recipe.first,
                                GrugShapelessRecipes.EMPTY_SLOT,
                                recipe.second)));
    }

    @Test
    void matchesARecipeWithOneIngredient() {
        Recipe recipe = new Recipe();
        GrugShapelessRecipes.register(new int[] {recipe.first}, recipe.output);

        assertSame(
                recipe.output,
                GrugShapelessRecipes.match(
                        grid(GrugShapelessRecipes.EMPTY_SLOT, recipe.first, GrugShapelessRecipes.EMPTY_SLOT)));
    }

    @Test
    void needsEveryIngredientItNames() {
        Recipe recipe = new Recipe();
        recipe.register();

        assertNull(
                GrugShapelessRecipes.match(
                        grid(recipe.first, GrugShapelessRecipes.EMPTY_SLOT, GrugShapelessRecipes.EMPTY_SLOT)));
    }

    @Test
    void needsNothingTheRecipeDoesNotName() {
        Recipe recipe = new Recipe();
        GrugShapelessRecipes.register(new int[] {recipe.first}, recipe.output);

        assertNull(
                GrugShapelessRecipes.match(
                        grid(recipe.first, GrugShapelessRecipes.EMPTY_SLOT, recipe.second)));
    }

    @Test
    void countsEachIngredientOnce() {
        Recipe recipe = new Recipe();
        GrugShapelessRecipes.register(new int[] {recipe.first, recipe.first}, recipe.output);

        assertSame(
                recipe.output,
                GrugShapelessRecipes.match(
                        grid(recipe.first, recipe.first, GrugShapelessRecipes.EMPTY_SLOT)));
    }

    @Test
    void matchesTheFirstRecipeThatFits() {
        Recipe skipped = new Recipe();
        GrugShapelessRecipes.register(
                new int[] {skipped.first, skipped.second}, skipped.output);
        Recipe fits = new Recipe();
        GrugShapelessRecipes.register(new int[] {fits.first, fits.second}, fits.output);

        assertSame(fits.output, GrugShapelessRecipes.match(grid(fits.first, fits.second)));
    }

    @Test
    void takesTheEarlierOfTwoRecipesThatBothFit() {
        Recipe ids = new Recipe();
        GrugShapelessRecipes.register(new int[] {ids.first, ids.second}, "registered first");
        GrugShapelessRecipes.register(new int[] {ids.first, ids.second}, "registered second");

        assertEquals(
                "registered first", GrugShapelessRecipes.match(grid(ids.first, ids.second)));
    }

    @Test
    void matchesNothingInAnEmptyGrid() {
        Recipe recipe = new Recipe();
        recipe.register();

        assertNull(
                GrugShapelessRecipes.match(
                        grid(
                                GrugShapelessRecipes.EMPTY_SLOT,
                                GrugShapelessRecipes.EMPTY_SLOT,
                                GrugShapelessRecipes.EMPTY_SLOT)));
    }

    @Test
    void countsWhatIsRegistered() {
        int before = GrugShapelessRecipes.registered();
        new Recipe().register();

        assertEquals(before + 1, GrugShapelessRecipes.registered());
    }
}
