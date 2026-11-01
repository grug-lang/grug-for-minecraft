package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Covers which of a recipe's ids resolve to the game objects this loader has, and what is reported
 * for the ones it does not.
 *
 * <p>A missing item in the mod's own {@code grug} namespace is a mod-tree defect, and every one of
 * them has to be reported: the defects a recipe carries are not only the first one found, so an
 * external item this version lacks must not hide the mod's own, in an ingredient or in the result.
 */
class GrugRecipeResolutionTest {

    private static final String PATH = "mymod/data/grug/recipes/crafting/thing.json";

    @TempDir Path mods;

    /** The ids the fake adapter knows, which stands in for a running game's registries. */
    private final Set<String> known = new HashSet<>();

    @BeforeEach
    @AfterEach
    void clearReports() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
    }

    /** A loader adapter whose registries hold exactly the ids the test declares. */
    private ModLoaderAdapter adapter() {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        GrugRecipeResolutionTest.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("createResourceLocation")) {
                                return args[0];
                            }
                            if (method.getName().equals("getItemFromRegistry")) {
                                String id = (String) args[0];
                                return known.contains(id) ? "item:" + id : null;
                            }
                            return null;
                        });
    }

    private void knows(String... ids) {
        known.addAll(Arrays.asList(ids));
    }

    private void file(String relativePath, String content) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /** The one recipe a test wrote, as the tree reads it back. */
    private GrugRecipeTree.Recipe recipe(String json) throws IOException {
        file(PATH, json);
        List<GrugRecipeTree.Recipe> recipes = GrugRecipeTree.recipes(mods.toFile());
        assertEquals(1, recipes.size());
        return recipes.get(0);
    }

    /** A shapeless recipe with the given ingredients and result. */
    private GrugRecipeTree.Recipe shapeless(String ingredients, String result) throws IOException {
        return recipe(
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": "
                        + ingredients
                        + ", \"result\": {\"id\": \""
                        + result
                        + "\"}}");
    }

    private GrugRecipeResolution.Resolved resolve(GrugRecipeTree.Recipe recipe) {
        return GrugRecipeResolution.resolve(recipe, adapter(), mods.toFile());
    }

    /** Every defect reported since the queue was last cleared. */
    private static List<String> reported() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            return new ArrayList<>(GrugErrors.runtimeErrorQueue);
        }
    }

    private static String needs(String itemId) {
        return "Recipe '"
                + PATH
                + "' needs the item '"
                + itemId
                + "', which this loader does not know.";
    }

    private static String gives(String itemId) {
        return "Recipe '" + PATH + "' gives '" + itemId + "', which this loader does not know.";
    }

    @Test
    void reportsEveryMissingOwnItemAfterAnExternalOne() throws IOException {
        GrugRecipeTree.Recipe recipe =
                shapeless(
                        "[{\"item\": \"minecraft:piston\"},"
                                + "{\"item\": \"grug:wooden_gear\"},"
                                + "{\"item\": \"grug:iron_gear\"}]",
                        "minecraft:stick");
        knows("minecraft:stick");

        GrugRecipeResolution.Resolved resolved = resolve(recipe);

        // The recipe is dropped, as it is when any ingredient is missing, but the piston this
        // version lacks does not hide the two grug items the mod failed to register.
        assertFalse(resolved.complete());
        assertNull(resolved.inputs());
        assertEquals(List.of(needs("grug:wooden_gear"), needs("grug:iron_gear")), reported());
    }

    @Test
    void reportsAMissingOwnResultOnceAnIngredientDroppedTheRecipe() throws IOException {
        GrugRecipeTree.Recipe recipe =
                shapeless("[{\"item\": \"minecraft:piston\"}]", "grug:wooden_engine");
        knows();

        GrugRecipeResolution.Resolved resolved = resolve(recipe);

        // The result is resolved even though the ingredient already dropped the recipe, so a grug
        // item that never registered cannot hide behind an item this version lacks.
        assertFalse(resolved.complete());
        assertNull(resolved.resultItem());
        assertEquals(List.of(gives("grug:wooden_engine")), reported());
    }

    @Test
    void reportsEveryMissingOwnItemOfAShapedRecipe() throws IOException {
        GrugRecipeTree.Recipe recipe =
                recipe(
                        "{\"type\": \"minecraft:crafting_shaped\","
                                + " \"pattern\": [\"ab\"],"
                                + " \"key\": {\"a\": {\"item\": \"grug:missing_gear\"},"
                                + "            \"b\": {\"item\": \"minecraft:piston\"}},"
                                + " \"result\": {\"id\": \"grug:missing_block\"}}");
        knows("minecraft:stick");

        GrugRecipeResolution.Resolved resolved = resolve(recipe);

        assertFalse(resolved.complete());
        assertNull(resolved.inputs());
        assertNull(resolved.resultItem());
        assertEquals(List.of(needs("grug:missing_gear"), gives("grug:missing_block")), reported());
    }

    @Test
    void resolvesAShapedRecipeTheLoaderHas() throws IOException {
        file("mymod/data/grug/tags/items/gears.json", "{\"values\": [\"grug:wooden_gear\"]}");
        GrugRecipeTree.Recipe recipe =
                recipe(
                        "{\"type\": \"minecraft:crafting_shaped\","
                                + " \"pattern\": [\" s \", \"s s\"],"
                                + " \"key\": {\"s\": {\"item\": \"minecraft:stick\"},"
                                + "            \"g\": {\"tag\": \"grug:gears\"}},"
                                + " \"result\": {\"id\": \"grug:thing\", \"count\": 2}}");
        knows("minecraft:stick", "grug:wooden_gear", "grug:thing");

        GrugRecipeResolution.Resolved resolved = resolve(recipe);

        assertTrue(resolved.complete());
        assertArrayEquals(
                new Object[] {
                    " s ",
                    "s s",
                    's',
                    "item:minecraft:stick",
                    'g',
                    // A tag resolves to the single item that fills it, which is what a registration
                    // can hold.
                    "item:grug:wooden_gear"
                },
                resolved.inputs());
        assertEquals("item:grug:thing", resolved.resultItem());
        assertEquals(List.of(), reported());
    }

    @Test
    void resolvesAShapelessRecipeTheLoaderHas() throws IOException {
        GrugRecipeTree.Recipe recipe =
                shapeless(
                        "[{\"item\": \"minecraft:stick\"}, {\"item\": \"grug:wooden_gear\"}]",
                        "grug:thing");
        knows("minecraft:stick", "grug:wooden_gear", "grug:thing");

        GrugRecipeResolution.Resolved resolved = resolve(recipe);

        assertTrue(resolved.complete());
        assertArrayEquals(
                new Object[] {"item:minecraft:stick", "item:grug:wooden_gear"}, resolved.inputs());
        assertEquals("item:grug:thing", resolved.resultItem());
        assertEquals(List.of(), reported());
    }

    @Test
    void reportsATagThatNoModShips() throws IOException {
        GrugRecipeTree.Recipe recipe = shapeless("[{\"tag\": \"c:gears\"}]", "minecraft:stick");
        knows("minecraft:stick");

        GrugRecipeResolution.Resolved resolved = resolve(recipe);

        assertFalse(resolved.complete());
        assertNull(resolved.inputs());
        assertEquals(
                List.of(
                        "Recipe '"
                                + PATH
                                + "' needs the tag 'c:gears', and no mod ships a tag file for"
                                + " 'c:gears'."),
                reported());
    }

    @Test
    void saysNothingAboutAMissingExternalResult() throws IOException {
        GrugRecipeTree.Recipe recipe =
                shapeless("[{\"item\": \"minecraft:stick\"}]", "minecraft:netherite_sword");
        knows("minecraft:stick");

        GrugRecipeResolution.Resolved resolved = resolve(recipe);

        // Every ingredient resolved, so only the missing vanilla result drops the recipe.
        assertFalse(resolved.complete());
        assertNull(resolved.resultItem());
        assertEquals(List.of(), reported());
    }

    @Test
    void saysNothingAboutAMissingItemWithoutANamespace() throws IOException {
        GrugRecipeTree.Recipe recipe = shapeless("[{\"item\": \"piston\"}]", "minecraft:stick");
        knows("minecraft:stick");

        GrugRecipeResolution.Resolved resolved = resolve(recipe);

        // An id without a namespace is a vanilla one, which this version may lack.
        assertFalse(resolved.complete());
        assertNull(resolved.inputs());
        assertEquals(List.of(), reported());
    }

    @Test
    void reportsAsTheCiRunFailsOnIt() throws IOException {
        GrugRecipeTree.Recipe recipe =
                shapeless("[{\"item\": \"grug:missing_gear\"}]", "minecraft:stick");
        knows("minecraft:stick");
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream saved = System.out;

        try {
            System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
            resolve(recipe);
        } finally {
            System.setOut(saved);
        }

        // The queue is what a loader drains into chat; the printed line is what run-loader.sh stops
        // the run on.
        assertEquals(List.of(needs("grug:missing_gear")), reported());
        assertTrue(
                printed.toString(StandardCharsets.UTF_8)
                        .contains("[GRUG CI] FAIL " + needs("grug:missing_gear")),
                printed.toString(StandardCharsets.UTF_8));
    }
}
