package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Covers the shaped matcher a game whose jar has no crafting package needs.
 *
 * <p>The table is static, as it has to be for the adapter that reads it, so each test works on item
 * ids of its own: a match returns the first recipe that fits, and a recipe an earlier test left
 * behind must not be the one answering. The ids are looked up in a map the test owns, which is how
 * the loader resolves a recipe's items but without a game to ask.
 */
class GrugShapedRecipesTest {

    private static int nextId = 2000;

    /**
     * Item ids and an output no other test uses, so nothing already registered can answer first.
     */
    private static final class Recipe {
        private final int first = nextId++;
        private final int second = nextId++;
        private final Object output = new Object();

        private final Map<Object, Integer> ids = new HashMap<>();
        private final GrugShapedRecipes.ItemIds lookup = ingredient -> ids.get(ingredient);

        Recipe() {
            ids.put("A", first);
            ids.put("B", second);
        }

        /**
         * Registers these rows with the symbol map the game's own registration takes, so a cell is
         * the id {@code ids} gives its character and a character absent from the map is a hole.
         */
        void register(Object... rows) {
            Object[] arguments = new Object[rows.length + 4];
            System.arraycopy(rows, 0, arguments, 0, rows.length);
            arguments[rows.length] = 'A';
            arguments[rows.length + 1] = "A";
            arguments[rows.length + 2] = 'B';
            arguments[rows.length + 3] = "B";
            registerRaw(arguments);
        }

        /** Registers exactly these arguments, which is what the game's registration receives. */
        void registerRaw(Object... arguments) {
            GrugShapedRecipes.registerShaped(arguments, output, lookup);
        }
    }

    /** A grid with only these cells filled, everything else empty. */
    private static int[] cells(int... slotsAndIds) {
        int[] grid = new int[9];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = GrugShapedRecipes.EMPTY_SLOT;
        }
        for (int i = 0; i < slotsAndIds.length; i += 2) {
            grid[slotsAndIds[i]] = slotsAndIds[i + 1];
        }
        return grid;
    }

    @Test
    void matchesInTheTopLeftCorner() {
        Recipe recipe = new Recipe();
        recipe.register("A");

        assertSame(recipe.output, GrugShapedRecipes.match(cells(0, recipe.first)));
    }

    @Test
    void matchesAtAnyOffsetAGridAllows() {
        // A one-wide column of two cells, at the bottom right corner of the grid and higher up.
        Recipe recipe = new Recipe();
        recipe.register("A", "B");

        assertSame(
                recipe.output, GrugShapedRecipes.match(cells(5, recipe.first, 8, recipe.second)));
        assertSame(
                recipe.output, GrugShapedRecipes.match(cells(1, recipe.first, 4, recipe.second)));
    }

    @Test
    void matchesThePatternAsWritten() {
        // "AB" with A left of B: only the unmirrored attempt fits it, because the mirrored attempt
        // wants B in the cell A fills.
        Recipe recipe = new Recipe();
        recipe.register("AB");

        assertSame(
                recipe.output, GrugShapedRecipes.match(cells(0, recipe.first, 1, recipe.second)));
        // The same pattern one row down, where the unmirrored attempt still fits.
        assertSame(
                recipe.output, GrugShapedRecipes.match(cells(3, recipe.first, 4, recipe.second)));
    }

    @Test
    void matchesThePatternMirrored() {
        // The same "AB" with B left of A: the pattern as written wants A first, so only the
        // mirrored attempt fits, which is the placement the game also accepts.
        Recipe recipe = new Recipe();
        recipe.register("AB");

        assertSame(
                recipe.output, GrugShapedRecipes.match(cells(0, recipe.second, 1, recipe.first)));
        assertSame(
                recipe.output, GrugShapedRecipes.match(cells(4, recipe.second, 5, recipe.first)));
    }

    @Test
    void doesNotMatchOneOrientationTheOtherCannotRescue() {
        // A top left and B two cells away on the diagonal is neither the pattern nor its mirror,
        // which needs A one right of B's column.
        Recipe recipe = new Recipe();
        recipe.register("A ", " B");

        assertNull(GrugShapedRecipes.match(cells(0, recipe.first, 8, recipe.second)));
    }

    @Test
    void keepsEveryCellThePatternLeavesEmptyEmpty() {
        Recipe recipe = new Recipe();
        recipe.register("A A");

        assertSame(recipe.output, GrugShapedRecipes.match(cells(0, recipe.first, 2, recipe.first)));
        // The hole in the middle is part of the pattern: an item there makes it a different grid.
        assertNull(
                GrugShapedRecipes.match(cells(0, recipe.first, 1, recipe.second, 2, recipe.first)));
    }

    @Test
    void needsTheIngredientAsOftenAsThePatternNamesIt() {
        Recipe recipe = new Recipe();
        recipe.register("AA");

        assertNull(GrugShapedRecipes.match(cells(0, recipe.first)));
        // Two cells of it, but stacked in a column rather than beside each other, is not the
        // pattern any offset or its mirror covers.
        assertNull(GrugShapedRecipes.match(cells(0, recipe.first, 3, recipe.first)));
        assertSame(recipe.output, GrugShapedRecipes.match(cells(0, recipe.first, 1, recipe.first)));
    }

    @Test
    void doesNotMatchWhenTheGridHasNoRoomForThePattern() {
        // Four cells in the corners have no 2 by 2 block among them, so a 2 by 2 recipe cannot
        // sit anywhere, while the same four cells below them can hold it.
        Recipe recipe = new Recipe();
        recipe.register("AA", "AA");

        assertNull(
                GrugShapedRecipes.match(
                        cells(
                                0, recipe.first,
                                2, recipe.first,
                                6, recipe.first,
                                8, recipe.first)));
        assertSame(
                recipe.output,
                GrugShapedRecipes.match(
                        cells(
                                3, recipe.first,
                                4, recipe.first,
                                6, recipe.first,
                                7, recipe.first)));
    }

    @Test
    void matchesAThreeByThreePatternOnlyOnTheWholeGrid() {
        Recipe recipe = new Recipe();
        recipe.register((Object) new String[] {"AAA", "AAA", "AAA"});

        int[] full =
                cells(
                        0,
                        recipe.first,
                        1,
                        recipe.first,
                        2,
                        recipe.first,
                        3,
                        recipe.first,
                        4,
                        recipe.first,
                        5,
                        recipe.first,
                        6,
                        recipe.first,
                        7,
                        recipe.first,
                        8,
                        recipe.first);
        assertSame(recipe.output, GrugShapedRecipes.match(full));

        full[4] = GrugShapedRecipes.EMPTY_SLOT;
        assertNull(GrugShapedRecipes.match(full));
    }

    @Test
    void readsTheRowsAsOneArrayOrAsOneArgumentEach() {
        Recipe recipe = new Recipe();
        recipe.registerRaw(new String[] {"A", "B"}, 'A', "A", 'B', "B");
        assertSame(
                recipe.output, GrugShapedRecipes.match(cells(0, recipe.first, 3, recipe.second)));

        Recipe separate = new Recipe();
        separate.registerRaw("A", "B", 'A', "A", 'B', "B");
        assertSame(
                separate.output,
                GrugShapedRecipes.match(cells(0, separate.first, 3, separate.second)));
    }

    @Test
    void readsAPatternThatNamesNoSymbols() {
        // A row and nothing after it: the arguments end before a symbol pair, so every cell the
        // row names is a hole, and an empty grid is the only one it can match.
        Recipe recipe = new Recipe();
        recipe.registerRaw("A");

        assertSame(recipe.output, GrugShapedRecipes.match(cells()));
        assertNull(GrugShapedRecipes.match(cells(0, recipe.first)));
    }

    @Test
    void matchesTheFirstRecipeThatFits() {
        Recipe skipped = new Recipe();
        skipped.register("AB");
        Recipe fits = new Recipe();
        fits.register("B");
        assertSame(fits.output, GrugShapedRecipes.match(cells(0, fits.second)));

        // Both recipes now answer this grid, and the one registered first is the one the game's own
        // list would reach first.
        Recipe first = new Recipe();
        first.register("A");
        Recipe second = new Recipe();
        second.ids.put("A", first.first);
        second.register("A");
        assertSame(first.output, GrugShapedRecipes.match(cells(0, first.first)));
    }

    @Test
    void matchesNothingWhenNoRecipeFits() {
        Recipe recipe = new Recipe();
        recipe.register("A");

        // An item no recipe names, where an empty grid belongs to a pattern that names no symbols.
        assertNull(GrugShapedRecipes.match(cells(4, Integer.MAX_VALUE)));
    }

    @Test
    void countsWhatIsRegistered() {
        int before = GrugShapedRecipes.registered();
        new Recipe().register("A");

        assertEquals(before + 1, GrugShapedRecipes.registered());
        assertEquals(GrugShapelessRecipes.EMPTY_SLOT, GrugShapedRecipes.EMPTY_SLOT);
    }
}
