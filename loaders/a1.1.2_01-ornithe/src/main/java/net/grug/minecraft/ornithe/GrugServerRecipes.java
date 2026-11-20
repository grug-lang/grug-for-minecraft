package net.grug.minecraft.ornithe;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugRecipeTree;
import net.grug.minecraft.grug.GrugShapedRecipes;
import net.grug.minecraft.grug.GrugShapelessRecipes;
import net.minecraft.block.Block;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * The recipe table of the Alpha a0.2.1 dedicated server (#1387).
 *
 * <p>Alpha crafted on the client, so the a0.2.1 server jar carries no crafting package at all: no
 * {@code CraftingManager}, no shaped recipe and no crafting table block entity. A grug block
 * entity's {@code update_recipe_output} therefore has nothing to ask there, and every grid would
 * report that it cannot compute a result, including the item-inserted export of an autocrafting
 * table. This class gives the server a table of its own, and answers a grid the way the client
 * would.
 *
 * <p>The recipe set is the client's own, because a grid has to answer the same on both sides: the
 * vanilla recipes {@code CraftingManager}'s constructor registers, transcribed from it in its own
 * order and from the same {@link Item} and {@link Block} constants (the server jar has the same
 * item and block fields as the client, only the crafting package is missing), then the mods' {@code
 * data/<namespace>/recipes} tree through the same {@link GrugRecipeParser} the client registers
 * from. The matching rule lives in core's {@link GrugShapedRecipes} and {@link
 * GrugShapelessRecipes}, which their own unit tests measure, and this class's own unit test walks
 * the table it registers, so the game's recipes are checked on every pull request rather than only
 * by the dispatch that launches a dedicated server.
 *
 * <p>A shaped recipe is asked first and a shapeless one only when none matched, which is the order
 * the client gives them: its {@code CraftingManager} holds the shaped list, and its mixin asks the
 * shapeless table after the game's own matching found nothing.
 */
public final class GrugServerRecipes {

    /** The nine cells a crafting grid reads, from the slot {@code update_recipe_output} names. */
    private static final int GRID_SLOTS = 9;

    private static boolean installed = false;

    @GrugGenerated("utility class: never instantiated")
    private GrugServerRecipes() {}

    /**
     * Where a registration goes: core's tables in production, and a recording table in the unit
     * test, so the game's own recipe table is measured rather than hidden behind a marker.
     */
    interface Tables {
        void shaped(ItemStack result, Object... arguments);

        void shapeless(int[] ingredientIds, ItemStack result);
    }

    /** The production tables, core's shaped and shapeless ones. */
    static final Tables CORE_TABLES =
            new Tables() {
                @Override
                public void shaped(ItemStack result, Object... arguments) {
                    GrugShapedRecipes.registerShaped(arguments, result, GrugServerRecipes::itemId);
                }

                @Override
                public void shapeless(int[] ingredientIds, ItemStack result) {
                    GrugShapelessRecipes.register(ingredientIds, result);
                }
            };

    /**
     * Builds the table once, on the first grid that asks for a result.
     *
     * <p>The client registers its recipes when its game is ready, and a0.2.1 has no server
     * lifecycle event to hang the same hook on, so the table is built on first use. That is late
     * enough for every block and item to be registered, which resolving a recipe's item ids needs,
     * and it is the only moment that needs them.
     *
     * <p>The mods directory arrives as a supplier so the unit test installs from its own tree, and
     * so a launched game resolves its directory only on the call that installs.
     */
    static void install(Supplier<File> grugModsDir) {
        if (installed) return;
        installed = true;
        registerVanilla(CORE_TABLES);
        registerMods(CORE_TABLES, grugModsDir.get());
    }

    /**
     * The result a grid crafts, or null when no recipe matches it.
     *
     * <p>The stack is the recipe's own, not a copy: the adapter copies it before caching one, so
     * taking a result from the cache cannot empty the recipe.
     */
    public static ItemStack result(int[] grid) {
        install(GrugModLoader::getActiveGrugModsDir);
        return firstMatch(grid);
    }

    /** The first recipe that answers the grid, shaped before shapeless. */
    static ItemStack firstMatch(int[] grid) {
        Object shaped = GrugShapedRecipes.match(grid);
        if (shaped != null) {
            return (ItemStack) shaped;
        }
        Object shapeless = GrugShapelessRecipes.match(grid);
        return shapeless != null ? (ItemStack) shapeless : null;
    }

    /**
     * The nine ids a grid starting at {@code startSlot} holds, with {@link
     * GrugShapedRecipes#EMPTY_SLOT} for an empty one.
     *
     * <p>A cell holds one id whatever the stack's size, so a stack of two wheat fills one cell and
     * counts once, as it does in the game's own matching.
     */
    static int[] gridOf(Inventory inventory, int startSlot) {
        int[] ids = new int[GRID_SLOTS];
        for (int i = 0; i < GRID_SLOTS; i++) {
            ItemStack stack = inventory.getItem(startSlot + i);
            ids[i] = stack != null ? stack.id : GrugShapedRecipes.EMPTY_SLOT;
        }
        return ids;
    }

    /** The mods' recipe tree, registered on the table. */
    static void registerMods(Tables tables, File grugModsDir) {
        registerMods(tables, GrugRecipeParser.parseAll(grugModsDir));
    }

    /** Parsed mod recipes, registered in the order the parser gives them. */
    static void registerMods(Tables tables, List<GrugRecipeParser.ParsedRecipe> recipes) {
        for (GrugRecipeParser.ParsedRecipe recipe : recipes) {
            if (recipe.kind() == GrugRecipeTree.Kind.SHAPELESS) {
                tables.shapeless(itemIds(recipe.inputs()), recipe.output());
            } else {
                tables.shaped(recipe.output(), recipe.inputs());
            }
        }
    }

    /** The ids of a shapeless recipe's ingredients, which are one item each. */
    static int[] itemIds(Object[] inputs) {
        int[] ids = new int[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            ids[i] = itemId(inputs[i]);
        }
        return ids;
    }

    /** The grid id of one ingredient: an item, or a block, which is its own item here. */
    static int itemId(Object ingredient) {
        if (ingredient instanceof Item) {
            return ((Item) ingredient).id;
        }
        if (ingredient instanceof Block) {
            return ((Block) ingredient).id;
        }
        throw Grug.fatal(
                "A recipe ingredient is neither an item nor a block: "
                        + ingredient.getClass().getName());
    }

    /**
     * The recipes {@code CraftingManager}'s constructor registers, transcribed from it and
     * registered on the table in its order.
     *
     * <p>{@code CraftingManager} sorts its own list by pattern size once, at the end of that
     * constructor, and every mod recipe is appended after the sort. No two of Alpha's recipes match
     * the same grid, so the sort cannot change which of them answers one; what it does order is the
     * grug recipes after them, which is this order.
     */
    static void registerVanilla(Tables tables) {
        new Builder(tables).vanilla();
    }

    /**
     * Builds the game's own recipe table.
     *
     * <p>This is a class rather than a free method because {@link #shaped} is called once per
     * recipe, and only the table it writes to differs between a launched server and the unit test.
     */
    private static final class Builder {
        private final Tables tables;

        private Builder(Tables tables) {
            this.tables = tables;
        }

        /** One shaped recipe, from the registration arguments a loader's parser hands out. */
        private void shaped(ItemStack result, Object... arguments) {
            tables.shaped(result, arguments);
        }

        private void vanilla() {
            toolRecipes();
            weaponRecipes();
            mineralRecipes();
            foodRecipes();
            blockRecipes();
            armorRecipes();
            shaped(new ItemStack(Item.PAPER, 3), "###", '#', Item.REEDS);
            shaped(new ItemStack(Item.BOOK, 1), "#", "#", "#", '#', Item.PAPER);
            shaped(new ItemStack(Block.FENCE, 2), "###", "###", '#', Item.STICK);
            shaped(
                    new ItemStack(Block.JUKEBOX, 1),
                    "###",
                    "#X#",
                    "###",
                    '#',
                    Block.PLANKS,
                    'X',
                    Item.DIAMOND);
            shaped(
                    new ItemStack(Block.BOOKSHELF, 1),
                    "###",
                    "XXX",
                    "###",
                    '#',
                    Block.PLANKS,
                    'X',
                    Item.BOOK);
            shaped(new ItemStack(Block.SNOW, 1), "##", "##", '#', Item.SNOWBALL);
            shaped(new ItemStack(Block.CLAY, 1), "##", "##", '#', Item.CLAY);
            shaped(new ItemStack(Block.BRICKS, 1), "##", "##", '#', Item.BRICK);
            shaped(new ItemStack(Block.WOOL, 1), "###", "###", "###", '#', Item.STRING);
            shaped(
                    new ItemStack(Block.TNT, 1),
                    "X#X",
                    "#X#",
                    "X#X",
                    'X',
                    Item.GUNPOWDER,
                    '#',
                    Block.SAND);
            shaped(new ItemStack(Block.STONE_SLAB, 3), "###", '#', Block.COBBLESTONE);
            shaped(new ItemStack(Block.LADDER, 1), "# #", "###", "# #", '#', Item.STICK);
            shaped(new ItemStack(Item.WOODEN_DOOR, 1), "##", "##", "##", '#', Block.PLANKS);
            shaped(new ItemStack(Item.IRON_DOOR, 1), "##", "##", "##", '#', Item.IRON_INGOT);
            shaped(
                    new ItemStack(Item.SIGN, 1),
                    "###",
                    "###",
                    " X ",
                    '#',
                    Block.PLANKS,
                    'X',
                    Item.STICK);
            shaped(new ItemStack(Block.PLANKS, 4), "#", '#', Block.LOG);
            shaped(new ItemStack(Item.STICK, 4), "#", "#", '#', Block.PLANKS);
            shaped(new ItemStack(Block.TORCH, 4), "X", "#", 'X', Item.COAL, '#', Item.STICK);
            shaped(new ItemStack(Item.BOWL, 4), "# #", " # ", '#', Block.PLANKS);
            shaped(
                    new ItemStack(Block.RAIL, 16),
                    "X X",
                    "X#X",
                    "X X",
                    'X',
                    Item.IRON_INGOT,
                    '#',
                    Item.STICK);
            shaped(new ItemStack(Item.MINECART, 1), "# #", "###", '#', Item.IRON_INGOT);
            shaped(
                    new ItemStack(Item.CHEST_MINECART, 1),
                    "A",
                    "B",
                    'A',
                    Block.CHEST,
                    'B',
                    Item.MINECART);
            shaped(
                    new ItemStack(Item.FURNACE_MINECART, 1),
                    "A",
                    "B",
                    'A',
                    Block.FURNACE,
                    'B',
                    Item.MINECART);
            shaped(new ItemStack(Item.BOAT, 1), "# #", "###", '#', Block.PLANKS);
            shaped(new ItemStack(Item.BUCKET, 1), "# #", " # ", '#', Item.IRON_INGOT);
            shaped(
                    new ItemStack(Item.FLINT_AND_STEEL, 1),
                    "A ",
                    " B",
                    'A',
                    Item.IRON_INGOT,
                    'B',
                    Item.FLINT);
            shaped(new ItemStack(Item.BREAD, 1), "###", '#', Item.WHEAT);
            shaped(new ItemStack(Block.OAK_STAIRS, 4), "#  ", "## ", "###", '#', Block.PLANKS);
            shaped(
                    new ItemStack(Item.FISHING_ROD, 1),
                    "  #",
                    " #X",
                    "# X",
                    '#',
                    Item.STICK,
                    'X',
                    Item.STRING);
            shaped(
                    new ItemStack(Block.STONE_STAIRS, 4),
                    "#  ",
                    "## ",
                    "###",
                    '#',
                    Block.COBBLESTONE);
            shaped(
                    new ItemStack(Item.PAINTING, 1),
                    "###",
                    "#X#",
                    "###",
                    '#',
                    Item.STICK,
                    'X',
                    Block.WOOL);
            shaped(
                    new ItemStack(Item.GOLDEN_APPLE, 1),
                    "###",
                    "#X#",
                    "###",
                    '#',
                    Block.GOLD_BLOCK,
                    'X',
                    Item.APPLE);
            shaped(
                    new ItemStack(Block.LEVER, 1),
                    "X",
                    "#",
                    '#',
                    Block.COBBLESTONE,
                    'X',
                    Item.STICK);
            shaped(
                    new ItemStack(Block.REDSTONE_TORCH, 1),
                    "X",
                    "#",
                    '#',
                    Item.STICK,
                    'X',
                    Item.REDSTONE);
            shaped(
                    new ItemStack(Item.COMPASS, 1),
                    " # ",
                    "#X#",
                    " # ",
                    '#',
                    Item.IRON_INGOT,
                    'X',
                    Item.REDSTONE);
            shaped(new ItemStack(Block.STONE_BUTTON, 1), "#", "#", '#', Block.STONE);
            shaped(new ItemStack(Block.STONE_PRESSURE_PLATE, 1), "###", '#', Block.STONE);
            shaped(new ItemStack(Block.WOODEN_PRESSURE_PLATE, 1), "###", '#', Block.PLANKS);
        }

        /** {@code ToolRecipes}: every tool, once per material. */
        private void toolRecipes() {
            String[][] patterns = {
                {"XXX", " # ", " # "}, {"X", "#", "#"}, {"XX", "X#", " #"}, {"XX", " #", " #"}
            };
            Object[] materials = {
                Block.PLANKS, Block.COBBLESTONE, Item.IRON_INGOT, Item.DIAMOND, Item.GOLD_INGOT
            };
            Item[][] tools = {
                {
                    Item.WOODEN_PICKAXE,
                    Item.STONE_PICKAXE,
                    Item.IRON_PICKAXE,
                    Item.DIAMOND_PICKAXE,
                    Item.GOLDEN_PICKAXE
                },
                {
                    Item.WOODEN_SHOVEL,
                    Item.STONE_SHOVEL,
                    Item.IRON_SHOVEL,
                    Item.DIAMOND_SHOVEL,
                    Item.GOLDEN_SHOVEL
                },
                {Item.WOODEN_AXE, Item.STONE_AXE, Item.IRON_AXE, Item.DIAMOND_AXE, Item.GOLDEN_AXE},
                {Item.WOODEN_HOE, Item.STONE_HOE, Item.IRON_HOE, Item.DIAMOND_HOE, Item.GOLDEN_HOE}
            };
            for (int material = 0; material < materials.length; material++) {
                for (int tool = 0; tool < patterns.length; tool++) {
                    shaped(
                            new ItemStack(tools[tool][material]),
                            patterns[tool],
                            '#',
                            Item.STICK,
                            'X',
                            materials[material]);
                }
            }
        }

        /** {@code WeaponRecipes}: every sword, once per material, and the bow and arrow. */
        private void weaponRecipes() {
            String[] pattern = {"X", "X", "#"};
            Object[] materials = {
                Block.PLANKS, Block.COBBLESTONE, Item.IRON_INGOT, Item.DIAMOND, Item.GOLD_INGOT
            };
            Item[] swords = {
                Item.WOODEN_SWORD,
                Item.STONE_SWORD,
                Item.IRON_SWORD,
                Item.DIAMOND_SWORD,
                Item.GOLDEN_SWORD
            };
            for (int material = 0; material < materials.length; material++) {
                shaped(
                        new ItemStack(swords[material]),
                        pattern,
                        '#',
                        Item.STICK,
                        'X',
                        materials[material]);
            }
            shaped(
                    new ItemStack(Item.BOW, 1),
                    " #X",
                    "# X",
                    " #X",
                    'X',
                    Item.STRING,
                    '#',
                    Item.STICK);
            shaped(
                    new ItemStack(Item.ARROW, 4),
                    "X",
                    "#",
                    "Y",
                    'Y',
                    Item.FEATHER,
                    'X',
                    Item.FLINT,
                    '#',
                    Item.STICK);
        }

        /** {@code MineralRecipes}: a storage block from nine of its item, and nine back. */
        private void mineralRecipes() {
            Block[] blocks = {Block.GOLD_BLOCK, Block.IRON_BLOCK, Block.DIAMOND_BLOCK};
            Item[] items = {Item.GOLD_INGOT, Item.IRON_INGOT, Item.DIAMOND};
            for (int i = 0; i < blocks.length; i++) {
                shaped(new ItemStack(blocks[i]), "###", "###", "###", '#', items[i]);
                shaped(new ItemStack(items[i], 9), "#", '#', blocks[i]);
            }
        }

        /** {@code FoodRecipes}: the mushroom stew, with either mushroom on top. */
        private void foodRecipes() {
            shaped(
                    new ItemStack(Item.MUSHROOM_STEW),
                    "Y",
                    "X",
                    "#",
                    'X',
                    Block.BROWN_MUSHROOM,
                    'Y',
                    Block.RED_MUSHROOM,
                    '#',
                    Item.BOWL);
            shaped(
                    new ItemStack(Item.MUSHROOM_STEW),
                    "Y",
                    "X",
                    "#",
                    'X',
                    Block.RED_MUSHROOM,
                    'Y',
                    Block.BROWN_MUSHROOM,
                    '#',
                    Item.BOWL);
        }

        /** {@code BlockRecipes}: the chest, the furnace and the crafting table. */
        private void blockRecipes() {
            shaped(new ItemStack(Block.CHEST), "###", "# #", "###", '#', Block.PLANKS);
            shaped(new ItemStack(Block.FURNACE), "###", "# #", "###", '#', Block.COBBLESTONE);
            shaped(new ItemStack(Block.CRAFTING_TABLE), "##", "##", '#', Block.PLANKS);
        }

        /** {@code ArmorRecipes}: every piece, once per material. */
        private void armorRecipes() {
            String[][] patterns = {
                {"XXX", "X X"}, {"X X", "XXX", "XXX"}, {"XXX", "X X", "X X"}, {"X X", "X X"}
            };
            Object[] materials = {
                Item.LEATHER, Block.FIRE, Item.IRON_INGOT, Item.DIAMOND, Item.GOLD_INGOT
            };
            Item[][] pieces = {
                {
                    Item.LEATHER_HELMET,
                    Item.CHAINMAIL_HELMET,
                    Item.IRON_HELMET,
                    Item.DIAMOND_HELMET,
                    Item.GOLDEN_HELMET
                },
                {
                    Item.LEATHER_CHESTPLATE,
                    Item.CHAINMAIL_CHESTPLATE,
                    Item.IRON_CHESTPLATE,
                    Item.DIAMOND_CHESTPLATE,
                    Item.GOLDEN_CHESTPLATE
                },
                {
                    Item.LEATHER_LEGGINGS,
                    Item.CHAINMAIL_LEGGINGS,
                    Item.IRON_LEGGINGS,
                    Item.DIAMOND_LEGGINGS,
                    Item.GOLDEN_LEGGINGS
                },
                {
                    Item.LEATHER_BOOTS,
                    Item.CHAINMAIL_BOOTS,
                    Item.IRON_BOOTS,
                    Item.DIAMOND_BOOTS,
                    Item.GOLDEN_BOOTS
                }
            };
            for (int material = 0; material < materials.length; material++) {
                for (int piece = 0; piece < patterns.length; piece++) {
                    shaped(
                            new ItemStack(pieces[piece][material]),
                            patterns[piece],
                            'X',
                            materials[material]);
                }
            }
        }
    }
}
