package net.minecraft.crafting;

import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugRecipeTree;
import net.grug.minecraft.grug.GrugShapelessRecipes;
import net.grug.minecraft.ornithe.GrugRecipeParser;
import net.minecraft.block.Block;
import net.minecraft.item.Item;

import java.io.File;

/**
 * Registers the recipes parsed by {@link GrugRecipeParser}.
 *
 * <p>This shim exists only because {@code CraftingManager.registerShaped} is package-private. The
 * actual parsing lives in {@code net.grug.*} so that Loom's remapping of the Minecraft namespace does
 * not change its runtime bytecode and break JaCoCo's class matching.
 *
 * <p>It lives in the loader rather than in {@code shared/ornithe} because Alpha 1.1.2_01 has no
 * shapeless recipe to register: its {@code CraftingManager} knows one recipe shape and one entry
 * point. A shapeless recipe therefore goes into {@link GrugShapelessRecipes}, which the loader's
 * {@code CraftingManagerMixin} asks before the game's own matching runs, so a mod that ships one
 * works here rather than being quietly unable to craft it.
 */
public class GrugRecipeHelper {
    public static void registerAutoDiscoveredRecipes(File grugModsDir) {
        for (GrugRecipeParser.ParsedRecipe recipe : GrugRecipeParser.parseAll(grugModsDir)) {
            if (recipe.kind() == GrugRecipeTree.Kind.SHAPELESS) {
                GrugShapelessRecipes.register(itemIds(recipe.inputs()), recipe.output());
            } else {
                CraftingManager.getInstance().registerShaped(recipe.output(), recipe.inputs());
            }
        }
    }

    /**
     * The grid's view of the ingredients, which is one id per slot.
     *
     * <p>A block is its own item form here, so its id is the id the grid holds for it, the same way
     * {@code CraftingManager} reads a block argument for a shaped recipe.
     */
    private static int[] itemIds(Object[] inputs) {
        int[] ids = new int[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            Object input = inputs[i];
            if (input instanceof Item item) {
                ids[i] = item.id;
            } else if (input instanceof Block block) {
                ids[i] = block.id;
            } else {
                throw Grug.fatal(
                        "A shapeless recipe ingredient is neither an item nor a block: "
                                + input.getClass().getName());
            }
        }
        return ids;
    }
}
