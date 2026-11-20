package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugRecipeTree;
import net.grug.minecraft.grug.GrugShapedRecipes;
import net.grug.minecraft.grug.GrugShapelessRecipes;
import net.minecraft.block.Block;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The recipe table the Alpha a0.2.1 dedicated server answers a crafting grid with (#1387).
 *
 * <p>The unit test walks the table's registrations through its {@link GrugServerRecipes.Tables}
 * seam, so the game's own recipes and the order they register in are checked on every pull request
 * rather than only by the dispatch that launches a dedicated server. The exact lookups below are
 * the cases the blocked tests craft, and the grid translation is a0.2.1's own: a cell is one id
 * whatever its stack's size, and an empty cell is {@link GrugShapedRecipes#EMPTY_SLOT}.
 */
class GrugServerRecipesTest {

    /** How many shaped recipes {@code CraftingManager}'s constructor registers. */
    private static final int VANILLA_RECIPES = 96;

    @TempDir static File emptyTree;

    @BeforeAll
    static void installTheTables() {
        // The first call builds both tables from the game's recipes and this empty mods tree; the
        // second covers the guard that makes every later grid reuse what it built.
        GrugServerRecipes.install(() -> emptyTree);
        GrugServerRecipes.install(() -> emptyTree);
    }

    @Test
    void readsOneIdPerCellWhateverTheStackSize() {
        FakeInventory inventory = new FakeInventory();
        inventory.slots[4] = new ItemStack(Item.DIAMOND.id, 2, 0);

        int[] grid = GrugServerRecipes.gridOf(inventory, 0);

        assertEquals(9, grid.length);
        assertEquals(Item.DIAMOND.id, grid[4], "the occupied cell holds the item's id");
        assertEquals(GrugShapedRecipes.EMPTY_SLOT, grid[0], "an empty cell holds no id");
        assertEquals(GrugShapedRecipes.EMPTY_SLOT, grid[3], "the hole beside it stays empty");
    }

    @Test
    void startsAtTheSlotTheGridIsNamedBy() {
        FakeInventory inventory = new FakeInventory();
        inventory.slots[12] = new ItemStack(Item.DIAMOND.id, 1, 0);

        int[] grid = GrugServerRecipes.gridOf(inventory, 10);

        assertEquals(
                Item.DIAMOND.id, grid[2], "slot 12 is the third cell of a grid starting at 10");
        assertEquals(GrugShapedRecipes.EMPTY_SLOT, grid[0]);
    }

    @Test
    void registersTheGameTableInTheClientsOrder() {
        RecordingTables tables = new RecordingTables();

        GrugServerRecipes.registerVanilla(tables);

        assertEquals(VANILLA_RECIPES, tables.shapedResults.size());
        assertTrue(tables.shapelessResults.isEmpty(), "Alpha has no shapeless recipe of its own");
        assertEquals(
                Item.WOODEN_PICKAXE.id,
                tables.shapedResults.get(0).id,
                "ToolRecipes registers the first recipe");
        assertEquals(
                Block.WOODEN_PRESSURE_PLATE.id,
                tables.shapedResults.get(VANILLA_RECIPES - 1).id,
                "the constructor's last recipe closes the table");
    }

    @Test
    void craftsTheVanillaBreadFromThreeWheat() {
        ItemStack bread =
                GrugServerRecipes.result(
                        cells(
                                0, Item.WHEAT.id,
                                1, Item.WHEAT.id,
                                2, Item.WHEAT.id));

        assertNotNull(bread);
        assertEquals(Item.BREAD.id, bread.id);
        assertEquals(1, bread.size);
    }

    @Test
    void craftsTheVanillaSignWhoseBottomRowLeavesItsMiddleCellEmpty() {
        int planks = Block.PLANKS.id;
        ItemStack sign =
                GrugServerRecipes.result(
                        cells(
                                0, planks,
                                1, planks,
                                2, planks,
                                3, planks,
                                4, planks,
                                5, planks,
                                7, Item.STICK.id));

        assertNotNull(sign);
        assertEquals(Item.SIGN.id, sign.id);
        assertEquals(1, sign.size);
    }

    @Test
    void craftsAStorageBlockFromNineAndTheNineBack() {
        int diamond = Item.DIAMOND.id;
        ItemStack block =
                GrugServerRecipes.result(
                        cells(
                                0, diamond,
                                1, diamond,
                                2, diamond,
                                3, diamond,
                                4, diamond,
                                5, diamond,
                                6, diamond,
                                7, diamond,
                                8, diamond));
        assertNotNull(block);
        assertEquals(Block.DIAMOND_BLOCK.id, block.id);
        assertEquals(1, block.size);

        ItemStack back = GrugServerRecipes.result(cells(4, Block.DIAMOND_BLOCK.id));
        assertNotNull(back);
        assertEquals(diamond, back.id);
        assertEquals(9, back.size);
    }

    @Test
    void craftsTheVanillaToolsAndSticks() {
        int planks = Block.PLANKS.id;
        ItemStack pickaxe =
                GrugServerRecipes.result(
                        cells(
                                0, planks,
                                1, planks,
                                2, planks,
                                4, Item.STICK.id,
                                7, Item.STICK.id));
        assertNotNull(pickaxe);
        assertEquals(Item.WOODEN_PICKAXE.id, pickaxe.id);

        ItemStack sticks = GrugServerRecipes.result(cells(0, planks, 3, planks));
        assertNotNull(sticks);
        assertEquals(Item.STICK.id, sticks.id);
        assertEquals(4, sticks.size);
    }

    @Test
    void answersNothingWhenNoRecipeFits() {
        assertNull(GrugServerRecipes.result(cells(4, 5999)));
    }

    @Test
    void answersShapedBeforeShapeless() {
        int ingredient = 6000;
        GrugShapedRecipes.registerShaped(
                new Object[] {"S", 'S', ingredient},
                new ItemStack(Item.DIAMOND, 1),
                ignored -> ingredient);
        GrugShapelessRecipes.register(new int[] {ingredient}, new ItemStack(Item.DIAMOND, 2));

        ItemStack answer = GrugServerRecipes.result(cells(4, ingredient));

        assertNotNull(answer);
        assertEquals(1, answer.size, "the shaped table answers before the shapeless one");
    }

    @Test
    void answersAShapelessRecipeWhenNoShapedRecipeFits() {
        int ingredient = 6003;
        GrugShapelessRecipes.register(new int[] {ingredient}, new ItemStack(Item.DIAMOND, 3));

        ItemStack answer = GrugServerRecipes.result(cells(4, ingredient));

        assertNotNull(answer);
        assertEquals(3, answer.size);
    }

    @Test
    void registersAShapelessRecipeOnTheCoreTable() {
        ItemStack output = new ItemStack(Item.DIAMOND, 5);
        GrugServerRecipes.CORE_TABLES.shapeless(new int[] {5998}, output);

        assertSame(output, GrugShapelessRecipes.match(cells(6, 5998)));
    }

    @Test
    void refusesAnIngredientThatIsNeitherAnItemNorABlock() {
        assertThrows(IllegalStateException.class, () -> GrugServerRecipes.itemId(new Object()));
        // The fatal is held until the next native call rethrows it; take it so it cannot surface in
        // another test of this JVM.
        assertThrows(IllegalStateException.class, GrugErrors::throwPendingFatal);
    }

    @Test
    void registersParsedModRecipesInOrder() {
        GrugRecipeParser.ParsedRecipe shaped =
                new GrugRecipeParser.ParsedRecipe(
                        new ItemStack(Item.DIAMOND, 2),
                        new Object[] {"X", 'X', Item.STICK},
                        GrugRecipeTree.Kind.SHAPED);
        GrugRecipeParser.ParsedRecipe shapeless =
                new GrugRecipeParser.ParsedRecipe(
                        new ItemStack(Item.APPLE, 3),
                        new Object[] {Item.STICK},
                        GrugRecipeTree.Kind.SHAPELESS);

        RecordingTables tables = new RecordingTables();
        GrugServerRecipes.registerMods(tables, Arrays.asList(shaped, shapeless));

        assertEquals(Arrays.asList("shaped", "shapeless"), tables.order);
        assertEquals(Item.DIAMOND.id, tables.shapedResults.get(0).id);
        assertEquals(2, tables.shapedResults.get(0).size);
        assertEquals(Item.APPLE.id, tables.shapelessResults.get(0).id);
        assertEquals(3, tables.shapelessResults.get(0).size);
    }

    /** A grid with only these cells filled, everything else empty. */
    private static int[] cells(int... slotsAndIds) {
        int[] grid = new int[9];
        Arrays.fill(grid, GrugShapedRecipes.EMPTY_SLOT);
        for (int i = 0; i < slotsAndIds.length; i += 2) {
            grid[slotsAndIds[i]] = slotsAndIds[i + 1];
        }
        return grid;
    }

    /** Records what a table would register, so a test can read the order and the results back. */
    private static final class RecordingTables implements GrugServerRecipes.Tables {
        private final List<String> order = new ArrayList<>();
        private final List<ItemStack> shapedResults = new ArrayList<>();
        private final List<ItemStack> shapelessResults = new ArrayList<>();

        @Override
        public void shaped(ItemStack result, Object... arguments) {
            order.add("shaped");
            shapedResults.add(result);
        }

        @Override
        public void shapeless(int[] ingredientIds, ItemStack result) {
            order.add("shapeless");
            shapelessResults.add(result);
        }
    }

    /** An inventory of 20 empty slots, which holds the grid a test names and nothing else. */
    private static final class FakeInventory implements Inventory {
        private final ItemStack[] slots = new ItemStack[20];

        @Override
        public int getSize() {
            return slots.length;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slots[slot];
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return null;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            slots[slot] = stack;
        }

        @Override
        public String getInventoryName() {
            return "fake inventory";
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public void markDirty() {}
    }
}
