package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.grug.GrugRecipeResolution;
import net.grug.minecraft.grug.GrugRecipeTree;
import net.minecraft.src.ItemStack;
import net.minecraft.src.ModLoader;

import java.io.File;

/**
 * Registers the recipes mods ship under {@code data/<namespace>/recipes} with this version's {@code
 * CraftingManager}.
 *
 * <p>1.2.5 has no data pack and no recipe registry of its own, so this is the whole path: {@link
 * GrugRecipeTree} finds and judges the files, {@link GrugRecipeResolution} turns each id into the
 * item that fills it, and the adapter turns an id into the game's item. {@code ModLoader.addRecipe}
 * and {@code addShapelessRecipe} are the public entry points to the package-private {@code
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
     * <p>A recipe is left out when it names an item this loader does not have, and reported only
     * when the missing item is in the mod's own namespace, so the run fails on a mod that names one
     * of its own items that never registered. A missing {@code minecraft} or other-mod item is the
     * ok outcome: a mod written for a modern version cannot know what an old one lacks, so the
     * recipe is simply not registered here, and the rest of the tree still loads. Every missing own
     * item is reported, not only the first, so an external item this version lacks cannot hide one.
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

    /** Builds one recipe's output and ingredients, or returns null once the recipe is dropped. */
    private static ParsedRecipe parse(
            GrugRecipeTree.Recipe recipe, ModLoaderAdapter adapter, File grugModsDir) {
        GrugRecipeResolution.Resolved resolved =
                GrugRecipeResolution.resolve(recipe, adapter, grugModsDir);
        if (!resolved.complete()) return null;

        return new ParsedRecipe(
                buildResult(adapter, resolved.resultItem(), recipe.resultCount()),
                resolved.inputs(),
                recipe.kind());
    }

    /** The recipe's output stack. */
    private static ItemStack buildResult(ModLoaderAdapter adapter, Object resultItem, int count) {
        return (ItemStack) adapter.createItemStack(resultItem, 0, count);
    }
}
