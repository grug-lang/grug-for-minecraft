package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugRecipeTree.Ingredient;
import net.grug.minecraft.grug.GrugRecipeTree.Kind;
import net.grug.minecraft.grug.GrugRecipeTree.Recipe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Covers the discovery and validation of the recipes mods ship. */
class GrugRecipeTreeTest {

    @TempDir Path mods;

    /**
     * Drains the fatal a walk failure provokes, and the defects the validator reports, so neither
     * can reach the next test on this thread.
     */
    @BeforeEach
    @AfterEach
    void drain() {
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.clear();
        }
        try {
            Grug.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive it.
        }
    }

    private void recipe(String relativePath, String content) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private List<Recipe> recipes() {
        return GrugRecipeTree.recipes(mods.toFile());
    }

    /** Every defect reported since the queue was last drained. */
    private static List<String> reported() {
        synchronized (Grug.runtimeErrorQueue) {
            return new ArrayList<>(Grug.runtimeErrorQueue);
        }
    }

    private static void assertReported(String message) {
        List<String> reported = reported();
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.get(0).contains(message), reported.get(0));
    }

    @Test
    void readsAShapedRecipe() throws IOException {
        recipe(
                "mymod/data/grug/recipes/crafting/thing.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\" s \", \"s s\"],"
                        + " \"key\": {\"s\": {\"item\": \"minecraft:stick\"},"
                        + "            \"g\": {\"tag\": \"c:gears/wooden\"}},"
                        + " \"result\": {\"id\": \"grug:thing\", \"count\": 3}}");

        List<Recipe> found = recipes();

        assertEquals(1, found.size());
        Recipe thing = found.get(0);
        assertEquals(Kind.SHAPED, thing.kind());
        assertEquals("mymod/data/grug/recipes/crafting/thing.json", thing.path());
        assertEquals(List.of(" s ", "s s"), thing.pattern());
        assertEquals(List.of('s', 'g'), thing.symbols());
        assertEquals(
                List.of(
                        new Ingredient("item", "minecraft:stick"),
                        new Ingredient("tag", "c:gears/wooden")),
                thing.ingredients());
        assertEquals("grug:thing", thing.resultId());
        assertEquals(3, thing.resultCount());
        assertEquals(
                mods.resolve("mymod/data/grug/recipes/crafting/thing.json").toFile(),
                thing.file());
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void readsAShapelessRecipe() throws IOException {
        recipe(
                "mymod/data/grug/recipes/thing.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"},"
                        + "                   {\"tag\": \"grug:planks\"}],"
                        + " \"result\": {\"item\": \"minecraft:dirt\"}}");

        List<Recipe> found = recipes();

        assertEquals(1, found.size());
        Recipe thing = found.get(0);
        assertEquals(Kind.SHAPELESS, thing.kind());
        assertEquals(List.of(), thing.pattern());
        assertEquals(
                List.of(
                        new Ingredient("item", "minecraft:dirt"),
                        new Ingredient("tag", "grug:planks")),
                thing.ingredients());
        assertEquals(1, thing.resultCount());
    }

    @Test
    void handsBackATypeItCannotJudge() throws IOException {
        // Which types a version can craft is the loader's to decide: the Ornithe loaders register
        // recipes themselves and have to say so, while the game itself ignores a recipe of a mod it
        // does not have. A type is never a defect here.
        recipe(
                "mymod/data/other/recipes/thing.json",
                "{\"type\": \"other:whatever\", \"result\": {\"id\": \"minecraft:dirt\"}}");

        List<Recipe> found = recipes();

        assertEquals(1, found.size());
        assertEquals(Kind.OTHER, found.get(0).kind());
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void findsRecipesInEveryModAndNamespace() throws IOException {
        recipe("aamod/data/one/recipes/a.json", "{\"type\": \"x:y\", \"result\": {\"id\": \"a\"}}");
        recipe("zzmod/data/two/recipes/deep/b.json", "{\"type\": \"x:y\", \"result\": {\"id\": \"b\"}}");

        assertEquals(
                List.of(
                        "aamod/data/one/recipes/a.json",
                        "zzmod/data/two/recipes/deep/b.json"),
                paths(GrugRecipeTree.files(mods.toFile()), mods.toFile()));
    }

    @Test
    void ignoresModsAndNamespacesWithoutRecipes() throws IOException {
        Files.createDirectories(mods.resolve("baremod"));
        recipe("mymod/data/grug/models/a.json", "{}");

        assertTrue(GrugRecipeTree.files(mods.toFile()).isEmpty());
    }

    @Test
    void returnsNothingForADirectoryThatIsNotThere() {
        assertTrue(GrugRecipeTree.files(new File(mods.toFile(), "missing")).isEmpty());
        assertTrue(GrugRecipeTree.recipes(new File(mods.toFile(), "missing")).isEmpty());
    }

    @Test
    void reportsJsonThatIsNotAnObject() throws IOException {
        recipe("mymod/data/grug/recipes/a.json", "[]");

        assertTrue(recipes().isEmpty());
        assertReported("Recipe 'mymod/data/grug/recipes/a.json' is not a JSON object.");
    }

    @Test
    void reportsJsonThatDoesNotParse() throws IOException {
        recipe("mymod/data/grug/recipes/a.json", "{ not json");

        assertTrue(recipes().isEmpty());
        assertReported("is not readable JSON");
    }

    @Test
    void reportsARecipeWithNoType() throws IOException {
        recipe("mymod/data/grug/recipes/a.json", "{\"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("names no recipe 'type'");
    }

    @Test
    void reportsAResultThatIsNotThere() throws IOException {
        recipe("mymod/data/grug/recipes/a.json", "{\"type\": \"minecraft:crafting_shapeless\"}");

        assertTrue(recipes().isEmpty());
        assertReported("names no 'result' object");
    }

    @Test
    void reportsAResultThatIsNotAnObject() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": \"minecraft:dirt\"}");

        assertTrue(recipes().isEmpty());
        assertReported("names no 'result' object");
    }

    @Test
    void reportsAResultThatNamesNoItem() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\", \"result\": {}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a 'result' that names no item");
    }

    @Test
    void reportsAResultCountThatIsNotAStack() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"id\": \"minecraft:dirt\", \"count\": 0}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a 'result' count of 0, which is not a stack");
    }

    @Test
    void reportsAResultCountThatIsNotANumber() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"id\": \"minecraft:dirt\", \"count\": \"lots\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a 'result' count that is not a number");
    }

    @Test
    void reportsAShapedRecipeWithNoPattern() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"key\": {\"s\": {\"item\": \"minecraft:dirt\"}},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("is shaped but names no 'pattern' array");
    }

    @Test
    void reportsAPatternNoGridHolds() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"\",\"\",\"\",\"\"],"
                        + " \"key\": {}, \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a pattern of 4 rows, which no crafting grid holds");
    }

    @Test
    void reportsAPatternRowNoGridHolds() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"    \"],"
                        + " \"key\": {}, \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a pattern row of 4 characters, which no crafting grid holds");
    }

    @Test
    void reportsAPatternRowThatIsNotAString() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [3],"
                        + " \"key\": {}, \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a pattern row that is not a string");
    }

    @Test
    void reportsPatternRowsOfDifferentWidths() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"ss\", \"s\"],"
                        + " \"key\": {\"s\": {\"item\": \"minecraft:dirt\"}},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has pattern rows of different widths");
    }

    @Test
    void reportsAShapedRecipeWithNoKey() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"s\"],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("is shaped but names no 'key' object");
    }

    @Test
    void reportsAPatternSymbolWithNoKeyEntry() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"st\"],"
                        + " \"key\": {\"s\": {\"item\": \"minecraft:dirt\"}},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("uses the pattern symbol 't' with no entry in 'key'");
    }

    @Test
    void reportsAKeyEntryThatIsNotOneCharacter() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"s\"],"
                        + " \"key\": {\"stick\": {\"item\": \"minecraft:dirt\"}},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a 'key' entry named 'stick' that is not one character");
    }

    @Test
    void reportsAKeyEntryThatNamesNeitherAnItemNorATag() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"s\"],"
                        + " \"key\": {\"s\": {\"data\": 0}},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a key entry 's' that names neither an 'item' nor a 'tag'");
    }

    @Test
    void readsATagWhenTheItemIsNotAnId() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": 5, \"tag\": \"grug:planks\"}],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertEquals(
                List.of(new Ingredient("tag", "grug:planks")), recipes().get(0).ingredients());
    }

    @Test
    void reportsAnEmptyPattern() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [],"
                        + " \"key\": {},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a pattern of 0 rows, which no crafting grid holds");
    }

    @Test
    void reportsAPatternRowThatIsNotText() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [{\"row\": \"s\"}],"
                        + " \"key\": {},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a pattern row that is not a string");
    }

    @Test
    void reportsAResultThatNamesAnEmptyId() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"id\": \"\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a 'result' that names no item");
    }

    @Test
    void reportsAnEmptyPatternRow() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"\"],"
                        + " \"key\": {},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a pattern row of 0 characters, which no crafting grid holds");
    }

    @Test
    void reportsAnIngredientThatIsNotAnObject() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [\"minecraft:dirt\"],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has ingredient 1 that is not an object");
    }

    @Test
    void reportsAnIngredientThatNamesNeitherAnItemNorATag() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"data\": 0}],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has ingredient 1 that names neither an 'item' nor a 'tag'");
    }

    @Test
    void reportsAShapelessRecipeWithNoIngredients() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("is shapeless with 0 ingredients, which no crafting grid holds");
    }

    @Test
    void reportsAShapelessRecipeNoGridHolds() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": ["
                        + " {\"item\": \"minecraft:dirt\"},{\"item\": \"minecraft:dirt\"},"
                        + " {\"item\": \"minecraft:dirt\"},{\"item\": \"minecraft:dirt\"},"
                        + " {\"item\": \"minecraft:dirt\"},{\"item\": \"minecraft:dirt\"},"
                        + " {\"item\": \"minecraft:dirt\"},{\"item\": \"minecraft:dirt\"},"
                        + " {\"item\": \"minecraft:dirt\"},{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("is shapeless with 10 ingredients, which no crafting grid holds");
    }

    @Test
    void reportsEveryBrokenRecipeAndKeepsTheGoodOne() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");
        recipe("mymod/data/grug/recipes/b.json", "{ not json");
        recipe("mymod/data/grug/recipes/c.json", "{\"result\": {\"id\": \"minecraft:dirt\"}}");

        List<Recipe> found = recipes();

        assertEquals(1, found.size());
        assertEquals("mymod/data/grug/recipes/a.json", found.get(0).path());
        assertEquals(2, reported().size(), reported().toString());
    }

    @Test
    void countsOneStackWhenTheResultNamesNoCount() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertEquals(1, recipes().get(0).resultCount());
    }

    @Test
    void acceptsASpacedPattern() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"   \"],"
                        + " \"key\": {},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertEquals(List.of(), recipes().get(0).ingredients());
    }

    @Test
    void keepsAKeyThePatternNeverUses() throws IOException {
        // A key entry the pattern never reaches is harmless: the game matches on the pattern, so the
        // entry simply sits there. Reporting it would fail a run over nothing.
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"s\"],"
                        + " \"key\": {\"s\": {\"item\": \"minecraft:dirt\"},"
                        + "            \"u\": {\"item\": \"minecraft:stick\"}},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        List<Recipe> found = recipes();
        assertEquals(1, found.size());
        assertEquals(List.of('s', 'u'), found.get(0).symbols());
        assertTrue(reported().isEmpty(), reported().toString());
    }

    @Test
    void reportsAKeyThatIsNotAnObject() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": [\"s\"],"
                        + " \"key\": [],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("is shaped but names no 'key' object");
    }

    @Test
    void reportsAPatternThatIsNotAnArray() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shaped\","
                        + " \"pattern\": {},"
                        + " \"key\": {\"s\": {\"item\": \"minecraft:dirt\"}},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("is shaped but names no 'pattern' array");
    }

    @Test
    void reportsAShapelessRecipeWithNoIngredientsArray() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("is shapeless but names no 'ingredients' array");
    }

    @Test
    void reportsIngredientsThatAreNotAnArray() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": {\"a\": 1},"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("is shapeless but names no 'ingredients' array");
    }
    @Test
    void reportsAnIngredientThatIsNull() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [null],"
                        + " \"result\": {\"id\": \"minecraft:dirt\"}}");

        assertTrue(recipes().isEmpty());
        assertReported("has ingredient 1 that is not an object");
    }

    @Test
    void reportsAResultItemThatIsNotAnId() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"id\": {}}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a 'result' that names no item");
    }

    @Test
    void reportsAResultItemThatIsANumber() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"item\": 5}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a 'result' that names no item");
    }

    @Test
    void reportsAResultCountThatIsNotAPrimitive() throws IOException {
        recipe(
                "mymod/data/grug/recipes/a.json",
                "{\"type\": \"minecraft:crafting_shapeless\","
                        + " \"ingredients\": [{\"item\": \"minecraft:dirt\"}],"
                        + " \"result\": {\"id\": \"minecraft:dirt\", \"count\": []}}");

        assertTrue(recipes().isEmpty());
        assertReported("has a 'result' count that is not a number");
    }

    @Test
    void describesAnIngredient() {
        Ingredient stick = new Ingredient("item", "minecraft:stick");

        assertEquals("item", stick.kind());
        assertEquals("minecraft:stick", stick.id());
        assertEquals(stick, new Ingredient("item", "minecraft:stick"));
        assertEquals(stick.hashCode(), new Ingredient("item", "minecraft:stick").hashCode());
        assertEquals("{item=minecraft:stick}", stick.toString());

        assertFalse(stick.equals(new Ingredient("tag", "minecraft:stick")));
        assertFalse(stick.equals(new Ingredient("item", "minecraft:dirt")));
        assertFalse(stick.equals("minecraft:stick"));
        assertFalse(stick.equals(null));
    }

    @Test
    void namesARecipeByItsPathInTheModsTree() {
        File outside = new File(mods.toFile(), "elsewhere/a.json");

        assertEquals("elsewhere/a.json", GrugRecipeTree.relativePath(mods.toFile(), outside));
        // A path outside the mods tree has no relative name to give, so it is reported whole.
        assertEquals(
                "/other/place/a.json",
                GrugRecipeTree.relativePath(mods.toFile(), new File("/other/place/a.json")));
    }

    private static List<String> paths(List<File> files, File modsDir) {
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(GrugRecipeTree.relativePath(modsDir, file));
        }
        return paths;
    }
}
