package net.minecraft.crafting;

import net.grug.minecraft.grug.GrugRecipeTree;
import net.grug.minecraft.ornithe.GrugRecipeParser;

import java.io.File;

/**
 * Registers the recipes parsed by {@link GrugRecipeParser}.
 *
 * <p>This shim exists only because {@code CraftingManager.registerShaped} and its shapeless twin
 * are package-private. The actual parsing lives in {@code net.grug.*} so that Loom's remapping of
 * the Minecraft namespace does not change its runtime bytecode and break JaCoCo's class matching.
 *
 * <p>It lives in the loader rather than in {@code shared/ornithe} because Beta 1.7.3's {@code
 * CraftingManager} can register a shapeless recipe and Alpha 1.1.2_01's cannot, so the two loaders
 * hand the same parsed recipe to different registrations.
 */
public class GrugRecipeHelper {
    public static void registerAutoDiscoveredRecipes(File grugModsDir) {
        for (GrugRecipeParser.ParsedRecipe recipe : GrugRecipeParser.parseAll(grugModsDir)) {
            if (recipe.kind() == GrugRecipeTree.Kind.SHAPELESS) {
                CraftingManager.getInstance().registerShapeless(recipe.output(), recipe.inputs());
            } else {
                CraftingManager.getInstance().registerShaped(recipe.output(), recipe.inputs());
            }
        }
    }
}
