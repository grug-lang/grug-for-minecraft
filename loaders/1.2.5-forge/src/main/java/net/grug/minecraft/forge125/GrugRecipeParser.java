package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.grug.GrugRecipeTree;
import net.grug.minecraft.grug.GrugTags;
import net.grug.minecraft.grug.GrugTags.Resolution;
import net.minecraft.src.ItemStack;
import net.minecraft.src.ModLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers the recipes mods ship under {@code data/<namespace>/recipes} with this version's {@code
 * CraftingManager}.
 *
 * <p>1.2.5 has no data pack and no recipe registry of its own, so this is the whole path: {@link
 * GrugRecipeTree} finds and judges the files, {@link GrugTags} resolves a tag to the item that
 * fills it, and the adapter turns an id into the game's item. {@code ModLoader.addRecipe} and
 * {@code addShapelessRecipe} are the public entry points to the package-private {@code
 * CraftingManager} methods.
 *
 * <p>The parsing rules live in {@code net.grug.*} so they are measured there; what is left here is
 * the part only a running game can answer.
 */
public final class GrugRecipeParser {
    private GrugRecipeParser() {}

    /** A parsed recipe, ready to hand to the game's registration. */
    private static final class ParsedRecipe {
        private final ItemStack output;
        private final Object[] inputs;
        private final GrugRecipeTree.Kind kind;

        private ParsedRecipe(ItemStack output, Object[] inputs, GrugRecipeTree.Kind kind) {
            this.output = output;
            this.inputs = inputs;
            this.kind = kind;
        }
    }

    /**
     * Registers every recipe in the mods tree that this loader can register.
     *
     * <p>A recipe grug cannot register is reported and left out rather than logged and skipped, so
     * the run fails on a mod that ships a recipe its players cannot craft, and the rest of the tree
     * still loads.
     */
    public static void registerAll(File grugModsDir) {
        ModLoaderAdapter adapter = GrugCore.getAdapter();

        for (GrugRecipeTree.Recipe recipe : GrugRecipeTree.recipes(grugModsDir)) {
            if (recipe.kind() == GrugRecipeTree.Kind.OTHER) {
                reportUnregisterable(recipe);
                continue;
            }

            ParsedRecipe parsed = parse(recipe, adapter, grugModsDir);
            if (parsed == null) continue;

            if (parsed.kind == GrugRecipeTree.Kind.SHAPED) {
                ModLoader.addRecipe(parsed.output, parsed.inputs);
            } else {
                ModLoader.addShapelessRecipe(parsed.output, parsed.inputs);
            }
        }
    }

    /**
     * Reports a recipe this version cannot register.
     *
     * <p>The Ornithe loaders skip a type whose namespace belongs to a mod they do not have, because
     * the game ignores such a recipe. This version has no mod registry to ask, so every type grug
     * cannot register is reported: a recipe the player cannot craft is a defect, and guessing that
     * its mod is absent would hide it.
     */
    private static void reportUnregisterable(GrugRecipeTree.Recipe recipe) {
        String type = recipe.type();
        if (!GrugRecipeTree.isWellFormedType(type)) {
            GrugModTreeDefect.report(
                    "Recipe '" + recipe.path() + "' has the malformed type '" + type + "'.");
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
            GrugRecipeTree.Recipe recipe, ModLoaderAdapter adapter, File grugModsDir) {
        Object[] inputs = inputs(recipe, adapter, grugModsDir);
        if (inputs == null) return null;

        ItemStack result =
                buildResult(adapter, recipe.resultId(), recipe.resultCount(), recipe.path());
        if (result == null) return null;

        return new ParsedRecipe(result, inputs, recipe.kind());
    }

    /**
     * The arguments the game's registration takes: the pattern rows and a symbol per key for a
     * shaped recipe, or one object per ingredient for a shapeless one.
     */
    private static Object[] inputs(
            GrugRecipeTree.Recipe recipe, ModLoaderAdapter adapter, File grugModsDir) {
        List<Object> inputs = new ArrayList<Object>();
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
            ModLoaderAdapter adapter,
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
            ModLoaderAdapter adapter, String resultId, int count, String recipePath) {
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
        resultStack.stackSize = count;
        return resultStack;
    }
}
