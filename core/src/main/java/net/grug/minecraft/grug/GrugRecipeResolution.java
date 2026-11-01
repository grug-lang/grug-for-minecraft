package net.grug.minecraft.grug;

import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.GrugTags.Resolution;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a recipe's ids to the game objects a loader's registration takes.
 *
 * <p>Whether an id names an item is the loader's answer, since only its registries know, so that
 * comes through its {@link ModLoaderAdapter}. The rule around the answer lives here, in {@code
 * net.grug.*}, so a Java test measures it and every loader applies it the same way.
 *
 * <p>A recipe is dropped when it names an item this loader does not have, and reported only when
 * the missing item is in the mod's own namespace: a mod cannot lack its own item, so one of its
 * files failed to register and the run fails on it. A missing {@code minecraft} or other-mod item
 * is the ok outcome, because a mod written for a modern version cannot know what an old one lacks.
 *
 * <p>Every ingredient and the result are resolved even after one of them turns out missing, since
 * each missing own item is a defect of its own: stopping at the first would let an external item
 * that an old version lacks hide a grug item that never registered.
 */
public final class GrugRecipeResolution {

    /** What one recipe resolved to on this loader, with null for whatever this loader lacks. */
    public static final class Resolved {
        private final Object[] inputs;
        private final Object resultItem;

        private Resolved(Object[] inputs, Object resultItem) {
            this.inputs = inputs;
            this.resultItem = resultItem;
        }

        /**
         * The arguments the game's registration takes: the pattern rows and a symbol per key for a
         * shaped recipe, or one object per ingredient for a shapeless one.
         */
        public Object[] inputs() {
            return inputs;
        }

        /** The game item the recipe produces. */
        public Object resultItem() {
            return resultItem;
        }

        /** Whether every ingredient and the result resolved, so the recipe can be registered. */
        public boolean complete() {
            return inputs != null && resultItem != null;
        }
    }

    @GrugGenerated("utility class: never instantiated")
    private GrugRecipeResolution() {}

    /**
     * Resolves one recipe, reporting each missing own item it names.
     *
     * <p>The result is resolved even when an ingredient is already missing, because a recipe that
     * cannot be registered may still report a defect of its own.
     */
    public static Resolved resolve(
            GrugRecipeTree.Recipe recipe, ModLoaderAdapter adapter, File grugModsDir) {
        return new Resolved(inputs(recipe, adapter, grugModsDir), result(recipe, adapter));
    }

    /**
     * The registration's arguments, or null once an ingredient is one this loader does not have.
     */
    private static Object[] inputs(
            GrugRecipeTree.Recipe recipe, ModLoaderAdapter adapter, File grugModsDir) {
        List<Object> inputs = new ArrayList<Object>();
        if (recipe.kind() == GrugRecipeTree.Kind.SHAPED) {
            inputs.addAll(recipe.pattern());
        }

        boolean complete = true;
        for (int i = 0; i < recipe.ingredients().size(); i++) {
            GrugRecipeTree.Ingredient ingredient = recipe.ingredients().get(i);
            Object item = ingredient(ingredient, adapter, grugModsDir, recipe.path());
            if (item == null) {
                complete = false;
                continue;
            }

            if (recipe.kind() == GrugRecipeTree.Kind.SHAPED) {
                inputs.add(recipe.symbols().get(i));
            }
            inputs.add(item);
        }

        return complete ? inputs.toArray() : null;
    }

    /** The game item the recipe produces, or null once this loader does not have it. */
    private static Object result(GrugRecipeTree.Recipe recipe, ModLoaderAdapter adapter) {
        Object item =
                adapter.getItemFromRegistry(adapter.createResourceLocation(recipe.resultId()));
        if (item == null) {
            reportOwnItemIfNeeded(recipe.path(), recipe.resultId(), "gives");
        }
        return item;
    }

    /**
     * The game object an ingredient names, or null once this loader does not have it.
     *
     * <p>An ingredient a tag names resolves to the one item that fills it, which is what a crafting
     * registration can hold: grug takes a single object per slot rather than a set to match
     * against.
     */
    private static Object ingredient(
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
            reportOwnItemIfNeeded(recipePath, itemId, "needs the item");
        }
        return item;
    }

    /**
     * Reports an unknown item only when it is one the mod cannot lack, and says nothing otherwise.
     *
     * <p>{@code verb} is the half of the report's sentence that differs between an ingredient
     * ({@code "needs the item"}) and a result ({@code "gives"}).
     *
     * <p>An unknown name from {@code minecraft} or another mod is an item this version may simply
     * not have, so the recipe that names it is dropped rather than reported: a mod written for a
     * modern version cannot know what an old one lacks. A name in the mod's own {@code grug}
     * namespace is different, because a mod cannot lack its own item: one of its files failed to
     * register, so the run fails on it.
     */
    private static void reportOwnItemIfNeeded(String recipePath, String itemId, String verb) {
        int separator = itemId.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : itemId.substring(0, separator);
        if (!"grug".equals(namespace)) return;
        GrugModTreeDefect.report(
                "Recipe '"
                        + recipePath
                        + "' "
                        + verb
                        + " '"
                        + itemId
                        + "', which this loader does not"
                        + " know.");
    }
}
