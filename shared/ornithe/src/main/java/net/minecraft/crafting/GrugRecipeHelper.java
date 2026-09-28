package net.minecraft.crafting;

import net.grug.minecraft.ornithe.GrugRecipeParser;

import java.io.File;

/**
 * Registers the recipes parsed by {@link GrugRecipeParser}.
 *
 * <p>This shim exists only because {@code CraftingManager.registerShaped} is package-private. The
 * actual parsing lives in {@code net.grug.*} so that Loom's remapping of the Minecraft namespace
 * does not change its runtime bytecode and break JaCoCo's class matching.
 */
public class GrugRecipeHelper {
    public static void registerAutoDiscoveredRecipes(File grugModsDir) {
        for (GrugRecipeParser.ParsedRecipe recipe : GrugRecipeParser.parseAll(grugModsDir)) {
            CraftingManager.getInstance().registerShaped(recipe.output(), recipe.inputs());
        }
    }
}
