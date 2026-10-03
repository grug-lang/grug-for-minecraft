package net.grug.minecraft.ornithe;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.grug.GrugRecipeTree;
import net.grug.minecraft.grug.GrugTags;
import net.grug.minecraft.grug.GrugTags.Resolution;
import net.minecraft.item.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the recipes mods' {@code data/<namespace>/recipes} trees declare into the ingredients
 * {@code CraftingManager} registers.
 *
 * <p>The parsing lives in {@code net.grug.*} rather than alongside {@code CraftingManager} because
 * Loom remaps mod classes that sit in Minecraft's namespace, which changes their runtime bytecode
 * and leaves JaCoCo unable to match them against the compiled class. Only the package-private
 * {@code CraftingManager.registerShaped} call needs to stay in the Minecraft namespace, in the thin
 * {@link net.minecraft.crafting.GrugRecipeHelper} shim.
 *
 * <p>Finding the recipes and deciding which of them are in the right shape is {@link
 * GrugRecipeTree}'s job, and resolving a tag to the item that fills it is {@link GrugTags}'s. What
 * is left here is the part only a running game can answer: whether an id names something in its
 * registries, and what to hand {@code CraftingManager} once it does.
 */
public final class GrugRecipeParser {
    private GrugRecipeParser() {}

    /**
     * A parsed recipe ready to hand to {@code CraftingManager.registerShaped} or its shapeless
     * twin.
     */
    public record ParsedRecipe(ItemStack output, Object[] inputs, GrugRecipeTree.Kind kind) {}

    /**
     * Parses every recipe in the mods tree that this loader can register.
     *
     * <p>A recipe grug cannot register is reported and left out rather than logged and skipped, so
     * the run fails on a mod that ships a recipe its players cannot craft, and the rest of the tree
     * still loads.
     */
    public static List<ParsedRecipe> parseAll(File grugModsDir) {
        List<ParsedRecipe> recipes = new ArrayList<>();
        OrnitheAdapter adapter = new OrnitheAdapter();

        for (GrugRecipeTree.Recipe recipe : GrugRecipeTree.recipes(grugModsDir)) {
            if (recipe.kind() == GrugRecipeTree.Kind.OTHER) {
                reportUnregisterable(recipe);
                continue;
            }
            ParsedRecipe parsed = parse(recipe, adapter, grugModsDir);
            if (parsed != null) {
                recipes.add(parsed);
            }
        }

        return recipes;
    }

    /**
     * Reports a recipe grug cannot register, unless its type belongs to a mod that is not loaded.
     *
     * <p>A mod may ship recipes for a mod it does not require, and the game ignores those when the
     * type's mod is absent, so that is not a defect. A type that names no resource id names nothing
     * at all, and a type whose mod is loaded but that grug still does not register leaves a recipe
     * its players cannot craft, so both of those are.
     */
    private static void reportUnregisterable(GrugRecipeTree.Recipe recipe) {
        String type = recipe.type();
        if (!GrugRecipeTree.isWellFormedType(type)) {
            GrugModTreeDefect.report(
                    "Recipe '" + recipe.path() + "' has the malformed type '" + type + "'.");
            return;
        }
        String namespace = type.substring(0, type.indexOf(':'));
        if (!"minecraft".equals(namespace) && !FabricLoader.getInstance().isModLoaded(namespace)) {
            return;
        }
        GrugModTreeDefect.report(
                "Recipe '"
                        + recipe.path()
                        + "' is a '"
                        + type
                        + "' recipe, which grug does not know how to register.");
    }

    /** Builds one recipe's output and ingredients, or reports and returns null. */
    private static ParsedRecipe parse(
            GrugRecipeTree.Recipe recipe, OrnitheAdapter adapter, File grugModsDir) {
        Object[] inputs = inputs(recipe, adapter, grugModsDir);
        if (inputs == null) return null;

        ItemStack result =
                buildResult(adapter, recipe.resultId(), recipe.resultCount(), recipe.path());
        if (result == null) return null;

        return new ParsedRecipe(result, inputs, recipe.kind());
    }

    /**
     * The arguments {@code CraftingManager} takes: the pattern rows and a symbol per key for a
     * shaped recipe, or one object per ingredient for a shapeless one.
     */
    private static Object[] inputs(
            GrugRecipeTree.Recipe recipe, OrnitheAdapter adapter, File grugModsDir) {
        List<Object> inputs = new ArrayList<>();
        if (recipe.kind() == GrugRecipeTree.Kind.SHAPED) {
            inputs.addAll(recipe.pattern());
        }

        for (int i = 0; i < recipe.ingredients().size(); i++) {
            GrugRecipeTree.Ingredient ingredient = recipe.ingredients().get(i);
            Object item = resolveIngredient(ingredient, adapter, grugModsDir, recipe.path());
            if (item == null) return null;

            if (recipe.kind() == GrugRecipeTree.Kind.SHAPED) {
                inputs.add(recipe.symbols().get(i));
            }
            inputs.add(item);
        }

        return inputs.toArray();
    }

    /**
     * The game object an ingredient names, or null once the reason has been reported.
     *
     * <p>An ingredient a tag names resolves to the one item that fills it, which is what a crafting
     * registration can hold: grug takes a single object per slot rather than a set to match
     * against.
     */
    private static Object resolveIngredient(
            GrugRecipeTree.Ingredient ingredient,
            OrnitheAdapter adapter,
            File grugModsDir,
            String recipePath) {
        String itemId;
        if ("tag".equals(ingredient.kind())) {
            Resolution resolution = GrugTags.firstItem(grugModsDir, ingredient.id());
            if (!resolution.resolved()) {
                GrugModTreeDefect.report(
                        "Recipe '"
                                + recipePath
                                + "' needs the tag '"
                                + ingredient.id()
                                + "', and "
                                + resolution.problem()
                                + ".");
                return null;
            }
            itemId = resolution.itemId();
        } else {
            itemId = ingredient.id();
        }

        Object item = adapter.getItemFromRegistry(adapter.createResourceLocation(itemId));
        if (item == null) {
            GrugModTreeDefect.report(
                    "Recipe '"
                            + recipePath
                            + "' needs the item '"
                            + itemId
                            + "', which this loader does not know.");
        }
        return item;
    }

    /** The recipe's output stack, or null once a result nothing resolves to has been reported. */
    private static ItemStack buildResult(
            OrnitheAdapter adapter, String resultId, int count, String recipePath) {
        Object resultItem = adapter.getItemFromRegistry(adapter.createResourceLocation(resultId));
        if (resultItem == null) {
            GrugModTreeDefect.report(
                    "Recipe '"
                            + recipePath
                            + "' gives '"
                            + resultId
                            + "', which this loader does not know.");
            return null;
        }

        ItemStack resultStack = (ItemStack) adapter.createItemStack(resultItem, 0);
        resultStack.size = count;
        return resultStack;
    }
}
