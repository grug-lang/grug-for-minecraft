package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shaped crafting for a game whose jar carries no crafting package at all.
 *
 * <p>Alpha 1.1.2_01 crafted on the client, so the a0.2.1 dedicated server jar has no {@code
 * CraftingManager} and no {@code Recipe} (#1387). A grug block entity's {@code
 * update_recipe_output} still has to answer a grid there, and the answer has to be the one the
 * client would give, so this holds the shaped recipes a server registers and matches a grid with
 * the client's own rule: a recipe is tried at every offset a 3 by 3 grid allows, as written and
 * mirrored left to right, and every cell the pattern does not cover has to be empty.
 *
 * <p>It is the shaped twin of {@link GrugShapelessRecipes}, and it keeps item ids for the same
 * reason: a legacy crafting grid holds one id per cell, so stack size and metadata are the recipe's
 * business and both the game's own matching and this ignore them. A stack of two of an item still
 * fills one cell, and a recipe that wants the item twice needs two cells.
 */
public final class GrugShapedRecipes {

    /**
     * The id a legacy grid holds for a slot with nothing in it, shared with the shapeless table
     * because it is the grid's own value rather than either matcher's.
     */
    public static final int EMPTY_SLOT = GrugShapelessRecipes.EMPTY_SLOT;

    /** The width and height of a crafting grid, on every version that has this table. */
    private static final int GRID = 3;

    /**
     * Turns one ingredient of a registration's arguments into the id a grid holds for it.
     *
     * <p>Whether an object is an item or a block, and which id it has, is the loader's answer: the
     * game's registries are the only thing that knows.
     */
    public interface ItemIds {
        int of(Object ingredient);
    }

    private static final List<Entry> entries = new ArrayList<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugShapedRecipes() {}

    /**
     * Registers a shaped recipe from the arguments the game's own registration takes.
     *
     * <p>{@code arguments} is the pattern first, either as one {@code String[]} of rows or as one
     * {@code String} per row, then a symbol and the ingredient it stands for, two entries at a
     * time, which is the shape {@code CraftingManager.registerShaped} reads and the shape {@link
     * GrugRecipeResolution} hands a loader for a mod's recipe. A cell whose character names no
     * symbol is empty, as it is in the game: {@code "X X"} has a hole in the middle.
     *
     * <p>Rows are joined in order and the pattern is flattened row by row, so the id at {@code
     * column + row * width} is the character at {@code row * width + column} of the joined rows.
     */
    public static void registerShaped(Object[] given, Object output, ItemIds ids) {
        // The row loop stops at the first argument that is not a row, and the end of the arguments
        // is also a stop: a pattern that names no symbol has no pair after it. A null sentinel past
        // the last argument supplies that stop, so the loop needs no bounds check of its own.
        Object[] arguments = Arrays.copyOf(given, given.length + 1);
        int pairsEnd = given.length;

        String pattern = "";
        int height = 0;
        int width = 0;
        int at = 0;
        if (arguments[at] instanceof String[]) {
            String[] rows = (String[]) arguments[at++];
            for (int row = 0; row < rows.length; row++) {
                height++;
                width = rows[row].length();
                pattern = pattern + rows[row];
            }
        } else {
            while (arguments[at] instanceof String) {
                String row = (String) arguments[at++];
                height++;
                width = row.length();
                pattern = pattern + row;
            }
        }

        Map<Character, Integer> keys = new HashMap<>();
        for (; at < pairsEnd; at += 2) {
            keys.put((Character) arguments[at], ids.of(arguments[at + 1]));
        }

        int[] ingredients = new int[width * height];
        for (int cell = 0; cell < ingredients.length; cell++) {
            Integer id = keys.get(pattern.charAt(cell));
            ingredients[cell] = id != null ? id : EMPTY_SLOT;
        }

        entries.add(new Entry(width, height, ingredients, output));
    }

    /** How many shaped recipes are registered, which is what the tests read back. */
    public static int registered() {
        return entries.size();
    }

    /**
     * The output of the first recipe {@code gridIds} matches, or null when none does.
     *
     * <p>The output is the recipe's own object rather than a copy, because copying it is the
     * caller's job: the client's {@code CraftingManager} hands its caller a fresh stack, and the
     * server's adapter copies before caching one, so a result taken from the cache cannot empty the
     * recipe.
     */
    public static Object match(int[] gridIds) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.matches(gridIds)) {
                return entry.output;
            }
        }
        return null;
    }

    /**
     * One shaped recipe, in the form {@code Recipe} holds: its pattern flattened row by row, with
     * {@link #EMPTY_SLOT} for a cell it leaves empty.
     */
    private static final class Entry {
        private final int width;
        private final int height;
        private final int[] ingredients;
        private final Object output;

        private Entry(int width, int height, int[] ingredients, Object output) {
            this.width = width;
            this.height = height;
            this.ingredients = ingredients;
            this.output = output;
        }

        /**
         * Whether the grid holds this pattern at any offset, mirrored or not.
         *
         * <p>Every offset is tried both ways rather than the recipe being normalized first, because
         * that is what {@code Recipe.matches} does and a pattern that is not symmetric answers the
         * same either way.
         */
        private boolean matches(int[] grid) {
            for (int x = 0; x + width <= GRID; x++) {
                for (int y = 0; y + height <= GRID; y++) {
                    if (matches(grid, x, y, true)) {
                        return true;
                    }
                    if (matches(grid, x, y, false)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Whether the pattern placed with its corner at {@code x}, {@code y} covers the whole grid,
         * reading it mirrored from the right when {@code mirrored} is set.
         */
        private boolean matches(int[] grid, int x, int y, boolean mirrored) {
            for (int column = 0; column < GRID; column++) {
                for (int row = 0; row < GRID; row++) {
                    int wanted = EMPTY_SLOT;
                    int patternColumn = column - x;
                    int patternRow = row - y;
                    if (patternColumn >= 0
                            && patternRow >= 0
                            && patternColumn < width
                            && patternRow < height) {
                        int cell =
                                mirrored
                                        ? width - patternColumn - 1 + patternRow * width
                                        : patternColumn + patternRow * width;
                        wanted = ingredients[cell];
                    }
                    if (grid[column + row * GRID] != wanted) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
