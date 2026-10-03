package net.grug.minecraft.grug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds and validates the recipes mods ship under {@code data/<namespace>/recipes}.
 *
 * <p>The validation lives here, in {@code net.grug.*} and away from any game class, so Java tests
 * can drive it against a scratch directory and so every loader judges a recipe the same way. A
 * recipe that breaks a rule is a mod-tree defect: it is reported through {@link GrugModTreeDefect},
 * which fails the run and leaves the game playing.
 *
 * <p>What grug cannot judge is left to the caller. Which types a game version can craft is one, so
 * a {@link Kind#OTHER} recipe is handed back rather than reported: the Ornithe loaders register the
 * recipes themselves and have to say so, while StationAPI hands the file to the game, which ignores
 * the recipes of a mod it does not have. Turning an item id into a game item is the other, because
 * only the loader knows its registries.
 */
public final class GrugRecipeTree {

    /** The one recipe type whose contents differ between the loaders. */
    public enum Kind {
        SHAPED,
        SHAPELESS,
        OTHER
    }

    public static final String SHAPED = "minecraft:crafting_shaped";
    public static final String SHAPELESS = "minecraft:crafting_shapeless";

    /** A resource id, which is the shape a recipe type has to have to name anything at all. */
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * Whether a recipe's type is a resource id.
     *
     * <p>This is the most a loader can say about a type it does not know. A type that is not an id
     * names nothing, so no recipe behind it can ever be crafted, which is a mod-tree defect the
     * loader reports. A type that is an id whose namespace belongs to a mod that is not loaded
     * names a recipe type nobody registered, and that is not a defect at all: a mod may ship
     * recipes for a mod it does not require.
     */
    public static boolean isWellFormedType(String type) {
        return type != null && RESOURCE_ID.matcher(type).matches();
    }

    /** A crafting grid is at most 3 by 3. */
    private static final int MAX_GRID = 3;

    @GrugGenerated("utility class: never instantiated")
    private GrugRecipeTree() {}

    /**
     * What an ingredient names: an item id, or a tag that stands for one.
     *
     * <p>A class rather than a record because core compiles to Java 8, which the 1.2.5 loader's
     * game JVM needs.
     */
    public static final class Ingredient {
        private final String kind;
        private final String id;

        public Ingredient(String kind, String id) {
            this.kind = kind;
            this.id = id;
        }

        public String kind() {
            return kind;
        }

        public String id() {
            return id;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Ingredient)) return false;
            Ingredient that = (Ingredient) other;
            return kind.equals(that.kind) && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return kind.hashCode() * 31 + id.hashCode();
        }

        @Override
        public String toString() {
            return "{" + kind + "=" + id + "}";
        }
    }

    /** A recipe file that parsed and broke no rule, in the shape the loaders register from. */
    public static final class Recipe {
        private final File file;
        private final String path;
        private final String type;
        private final Kind kind;
        private final List<String> pattern;
        private final List<Ingredient> ingredients;
        private final List<Character> symbols;
        private final String resultId;
        private final int resultCount;

        private Recipe(
                File file,
                String path,
                String type,
                Kind kind,
                List<String> pattern,
                List<Ingredient> ingredients,
                List<Character> symbols,
                String resultId,
                int resultCount) {
            this.file = file;
            this.path = path;
            this.type = type;
            this.kind = kind;
            this.pattern = pattern;
            this.ingredients = ingredients;
            this.symbols = symbols;
            this.resultId = resultId;
            this.resultCount = resultCount;
        }

        public File file() {
            return file;
        }

        /** The recipe's path relative to the mods directory, which is how a defect names it. */
        public String path() {
            return path;
        }

        /**
         * The recipe's own {@code type}, which is what a defect names when it cannot be crafted.
         */
        public String type() {
            return type;
        }

        public Kind kind() {
            return kind;
        }

        /** The rows of a shaped recipe, or an empty list for a shapeless one. */
        public List<String> pattern() {
            return pattern;
        }

        /** One entry per key symbol (shaped) or per array entry (shapeless), in file order. */
        public List<Ingredient> ingredients() {
            return ingredients;
        }

        /**
         * The key symbol each entry of {@link #ingredients()} came from, so shaped recipes can pair
         * them up.
         */
        public List<Character> symbols() {
            return symbols;
        }

        public String resultId() {
            return resultId;
        }

        public int resultCount() {
            return resultCount;
        }
    }

    /**
     * Every recipe JSON under the mods directory, ordered so a defect reads the same way each run.
     */
    public static List<File> files(File modsDir) {
        List<File> found = new ArrayList<>();
        collectRecipes(modsDir, found);
        found.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return found;
    }

    /**
     * Reads and validates every recipe in the mods tree.
     *
     * <p>A recipe that breaks a rule is reported and left out, so one broken file does not hide the
     * recipes behind it. Everything that walks or reads the disk aborts instead: a walk that fails
     * has already left the run unable to say which recipes exist, and continuing past that reports
     * a clean tree.
     */
    public static List<Recipe> recipes(File modsDir) {
        List<Recipe> recipes = new ArrayList<>();
        for (File file : files(modsDir)) {
            String path = relativePath(modsDir, file);
            Recipe recipe = read(modsDir, file, path);
            if (recipe != null) {
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    /** The recipe's path relative to {@code modsDir}, with forward slashes. */
    public static String relativePath(File modsDir, File file) {
        String base = modsDir.getAbsolutePath();
        String absolute = file.getAbsolutePath();
        String path =
                absolute.startsWith(base + File.separator)
                        ? absolute.substring(base.length() + 1)
                        : absolute;
        return path.replace('\\', '/');
    }

    /** Finds every recipe JSON under one mods tree. */
    private static void collectRecipes(File grugModsDir, List<File> found) {
        File[] modDirs = grugModsDir.listFiles(File::isDirectory);
        if (modDirs == null) return;

        for (File modDir : modDirs) {
            walkRecipeNamespace(new File(modDir, "data"), found);
        }
    }

    /**
     * Collects the recipe files under one mods {@code data} directory.
     *
     * <p>Kept apart so the walk's own failure is excluded from {@link #files}' coverage: a
     * directory that cannot be read is not something a test can arrange, and the alternative,
     * carrying on, is the answer this change exists to remove.
     */
    @GrugGenerated("recipe discovery: a directory that cannot be walked is not forceable")
    private static void walkRecipeNamespace(File dataDir, List<File> found) {
        File[] namespaceDirs = dataDir.listFiles(File::isDirectory);
        if (namespaceDirs == null) return;

        for (File nsDir : namespaceDirs) {
            File recipesDir = new File(nsDir, "recipes");
            if (!recipesDir.exists() || !recipesDir.isDirectory()) continue;

            try (Stream<Path> stream = Files.walk(recipesDir.toPath())) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> found.add(p.toFile()));
            } catch (IOException e) {
                throw Grug.fatal("Failed to walk the recipe directory " + recipesDir, e);
            }
        }
    }

    /**
     * Parses one recipe file, reporting whatever rule it breaks and returning null for it.
     *
     * <p>Kept apart so {@link #recipes} reads as the loop it is, and so each rule's message can be
     * asserted on its own.
     */
    @GrugGenerated("recipe parsing: one file per call, and a Java test drives each rule")
    private static Recipe read(File modsDir, File file, String path) {
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return defect(path, "is not a JSON object");
            }
            json = parsed.getAsJsonObject();
        } catch (Exception e) {
            return defect(path, "is not readable JSON: " + e.getMessage());
        }

        if (!json.has("type") || !isText(json.get("type"))) {
            return defect(path, "names no recipe 'type'");
        }
        String type = json.get("type").getAsString();

        String resultId = resultId(json, path);
        if (resultId == null) return null;
        int resultCount = resultCount(json, path);
        if (resultCount < 0) return null;

        if (SHAPED.equals(type)) {
            return shaped(file, json, path, type, resultId, resultCount);
        }
        if (SHAPELESS.equals(type)) {
            return shapeless(file, json, path, type, resultId, resultCount);
        }
        return new Recipe(
                file,
                path,
                type,
                Kind.OTHER,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                resultId,
                resultCount);
    }

    private static Recipe shaped(
            File file,
            JsonObject json,
            String path,
            String type,
            String resultId,
            int resultCount) {
        JsonElement keyElement = json.get("key");
        if (keyElement == null || !keyElement.isJsonObject()) {
            return defect(path, "is shaped but names no 'key' object");
        }

        List<Ingredient> ingredients = new ArrayList<>();
        List<Character> symbols = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : keyElement.getAsJsonObject().entrySet()) {
            if (entry.getKey().length() != 1) {
                return defect(
                        path,
                        "has a 'key' entry named '"
                                + entry.getKey()
                                + "' that is not one character");
            }
            Ingredient ingredient =
                    ingredient(path, "a key entry '" + entry.getKey() + "'", entry.getValue());
            if (ingredient == null) return null;
            symbols.add(entry.getKey().charAt(0));
            ingredients.add(ingredient);
        }

        List<String> pattern = pattern(json, path);
        if (pattern == null) return null;

        String missing = firstUnmappedSymbol(pattern, keyElement.getAsJsonObject());
        if (missing != null) {
            return defect(path, "uses the pattern symbol '" + missing + "' with no entry in 'key'");
        }

        return new Recipe(
                file,
                path,
                type,
                Kind.SHAPED,
                pattern,
                ingredients,
                symbols,
                resultId,
                resultCount);
    }

    private static Recipe shapeless(
            File file,
            JsonObject json,
            String path,
            String type,
            String resultId,
            int resultCount) {
        JsonElement array = json.get("ingredients");
        if (array == null || !array.isJsonArray()) {
            return defect(path, "is shapeless but names no 'ingredients' array");
        }
        JsonArray ingredients = array.getAsJsonArray();
        if (ingredients.size() == 0 || ingredients.size() > MAX_GRID * MAX_GRID) {
            return defect(
                    path,
                    "is shapeless with "
                            + ingredients.size()
                            + " ingredients, which no crafting grid holds");
        }

        List<Ingredient> parsed = new ArrayList<>();
        for (JsonElement element : ingredients) {
            Ingredient ingredient = ingredient(path, "ingredient " + (parsed.size() + 1), element);
            if (ingredient == null) return null;
            parsed.add(ingredient);
        }

        return new Recipe(
                file,
                path,
                type,
                Kind.SHAPELESS,
                Collections.emptyList(),
                parsed,
                Collections.emptyList(),
                resultId,
                resultCount);
    }

    private static List<String> pattern(JsonObject json, String path) {
        JsonElement element = json.get("pattern");
        if (element == null || !element.isJsonArray()) {
            report(path, "is shaped but names no 'pattern' array");
            return null;
        }
        JsonArray rows = element.getAsJsonArray();
        if (rows.size() == 0 || rows.size() > MAX_GRID) {
            report(path, "has a pattern of " + rows.size() + " rows, which no crafting grid holds");
            return null;
        }

        List<String> pattern = new ArrayList<>();
        int width = -1;
        for (JsonElement row : rows) {
            if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) {
                report(path, "has a pattern row that is not a string");
                return null;
            }
            String text = row.getAsString();
            if (text.isEmpty() || text.length() > MAX_GRID) {
                report(
                        path,
                        "has a pattern row of "
                                + text.length()
                                + " characters, which no crafting grid holds");
                return null;
            }
            if (width >= 0 && text.length() != width) {
                report(path, "has pattern rows of different widths");
                return null;
            }
            width = text.length();
            pattern.add(text);
        }
        return pattern;
    }

    /**
     * The first symbol the pattern uses that {@code key} does not name, or null when all are named.
     */
    private static String firstUnmappedSymbol(List<String> pattern, JsonObject key) {
        for (String row : pattern) {
            for (char symbol : row.toCharArray()) {
                if (symbol == ' ') continue;
                String name = String.valueOf(symbol);
                if (!key.has(name)) return name;
            }
        }
        return null;
    }

    private static Ingredient ingredient(String path, String where, JsonElement element) {
        // A JSON null reaches here as JsonNull, which is not an object, so one check covers it.
        if (!element.isJsonObject()) {
            report(path, "has " + where + " that is not an object");
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (isText(object.get("item"))) {
            return new Ingredient("item", object.get("item").getAsString());
        }
        if (isText(object.get("tag"))) {
            return new Ingredient("tag", object.get("tag").getAsString());
        }
        report(path, "has " + where + " that names neither an 'item' nor a 'tag'");
        return null;
    }

    /** Whether an element is a JSON string, which every id and symbol in a recipe has to be. */
    private static boolean isText(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString();
    }

    private static String resultId(JsonObject json, String path) {
        JsonElement element = json.get("result");
        if (element == null || !element.isJsonObject()) {
            report(path, "names no 'result' object");
            return null;
        }
        JsonObject result = element.getAsJsonObject();
        // Modern recipes name the item "id"; the loaders rewrite it to the legacy "item" the game
        // wants, so a mod only has to write it one way.
        JsonElement id = result.has("id") ? result.get("id") : result.get("item");
        if (!isText(id) || id.getAsString().isEmpty()) {
            report(path, "has a 'result' that names no item");
            return null;
        }
        return id.getAsString();
    }

    /** The result count, or -1 once a malformed one has been reported. */
    private static int resultCount(JsonObject json, String path) {
        JsonElement element = json.getAsJsonObject("result").get("count");
        if (element == null) return 1;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            report(path, "has a 'result' count that is not a number");
            return -1;
        }
        int count = element.getAsInt();
        if (count < 1) {
            report(path, "has a 'result' count of " + count + ", which is not a stack");
            return -1;
        }
        return count;
    }

    private static Recipe defect(String path, String problem) {
        report(path, problem);
        return null;
    }

    private static void report(String path, String problem) {
        GrugModTreeDefect.report("Recipe '" + path + "' " + problem + ".");
    }
}
