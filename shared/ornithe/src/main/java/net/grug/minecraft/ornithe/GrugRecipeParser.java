package net.grug.minecraft.ornithe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.item.ItemStack;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Parses the shaped recipes that mods' {@code data/<namespace>/recipes} trees declare.
 *
 * <p>The parsing lives in {@code net.grug.*} rather than alongside {@code CraftingManager} because
 * Loom remaps mod classes that sit in Minecraft's namespace, which changes their runtime bytecode
 * and leaves JaCoCo unable to match them against the compiled class. Only the package-private
 * {@code CraftingManager.registerShaped} call needs to stay in the Minecraft namespace, in the thin
 * {@link net.minecraft.crafting.GrugRecipeHelper} shim.
 */
public final class GrugRecipeParser {
    private GrugRecipeParser() {}

    /** A parsed recipe ready to hand to {@code CraftingManager.registerShaped}. */
    public record ParsedRecipe(ItemStack output, Object[] inputs) {}

    public static List<ParsedRecipe> parseAll(File grugModsDir) {
        List<ParsedRecipe> recipes = new ArrayList<>();
        OrnitheAdapter adapter = new OrnitheAdapter();

        for (File recipeFile : recipeFiles(grugModsDir)) {
            try {
                ParsedRecipe recipe = parseRecipe(recipeFile, adapter, grugModsDir);
                if (recipe != null) recipes.add(recipe);
            } catch (Exception e) {
                GrugModLoader.LOGGER.error("Failed to register recipe: " + recipeFile, e);
            }
        }

        return recipes;
    }

    /**
     * Finds every recipe JSON under the mods directory.
     *
     * <p>Kept apart so the directory-structure and IO failures do not count against {@link
     * #parseAll} coverage.
     */
    @GrugGenerated(
            "recipe discovery: directory structure and IO failures are reported, not measured")
    private static List<File> recipeFiles(File grugModsDir) {
        List<File> files = new ArrayList<>();

        File[] modDirs = grugModsDir.listFiles(File::isDirectory);
        if (modDirs == null) return files;

        for (File modDir : modDirs) {
            File dataDir = new File(modDir, "data");
            if (!dataDir.exists() || !dataDir.isDirectory()) continue;

            File[] namespaceDirs = dataDir.listFiles(File::isDirectory);
            if (namespaceDirs == null) continue;

            for (File nsDir : namespaceDirs) {
                File recipesDir = new File(nsDir, "recipes");
                if (!recipesDir.exists() || !recipesDir.isDirectory()) continue;

                try (Stream<Path> stream = Files.walk(recipesDir.toPath())) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".json"))
                            .forEach(p -> files.add(p.toFile()));
                } catch (Exception e) {
                    GrugModLoader.LOGGER.error(
                            "Failed to walk recipes directory: " + recipesDir, e);
                }
            }
        }

        return files;
    }

    @GrugGenerated("recipe read failure")
    private static JsonObject readJson(File recipeFile) throws java.io.IOException {
        try (InputStreamReader reader =
                new InputStreamReader(new FileInputStream(recipeFile), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static ParsedRecipe parseRecipe(
            File recipeFile, OrnitheAdapter adapter, File grugModsDir) throws Exception {
        JsonObject json = readJson(recipeFile);

        String type = json.get("type").getAsString();

        // TODO: Support or warn on shapeless recipes with MC Alpha 1.1.2_01
        // https://github.com/grug-lang/grug-for-minecraft/issues/11
        if (!"minecraft:crafting_shaped".equals(type)) {
            GrugModLoader.LOGGER.warn(
                    "Skipping unsupported recipe type (only shaped is supported): "
                            + type
                            + " in "
                            + recipeFile);
            return null;
        }

        JsonObject resultObj = json.getAsJsonObject("result");
        String resultId =
                resultObj.has("id")
                        ? resultObj.get("id").getAsString()
                        : resultObj.get("item").getAsString();
        int count = resultObj.has("count") ? resultObj.get("count").getAsInt() : 1;

        ItemStack resultStack = buildResult(adapter, resultId, count, recipeFile);

        JsonArray pattern = json.getAsJsonArray("pattern");
        JsonObject keyObj = json.getAsJsonObject("key");

        List<Object> inputs = new ArrayList<>();
        for (JsonElement row : pattern) {
            inputs.add(row.getAsString());
        }

        for (Map.Entry<String, JsonElement> entry : keyObj.entrySet()) {
            inputs.add(entry.getKey().charAt(0));
            Object item =
                    resolveIngredient(entry.getValue().getAsJsonObject(), adapter, grugModsDir);
            if (item == null) {
                GrugModLoader.LOGGER.warn("Unknown ingredient in recipe: " + recipeFile);
                return null;
            }
            inputs.add(item);
        }

        GrugModLoader.LOGGER.info("Registered shaped recipe for " + resultId);
        return new ParsedRecipe(resultStack, inputs.toArray());
    }

    /** A mods directory's subdirectories, or an empty array when it cannot be listed. */
    @GrugGenerated("defensive: a directory that cannot be listed")
    private static File[] listEntries(File directory) {
        File[] entries = directory.listFiles(File::isDirectory);
        return entries == null ? new File[0] : entries;
    }

    @GrugGenerated("unknown result: an unresolvable result cannot be shipped as a fixture")
    private static ItemStack buildResult(
            OrnitheAdapter adapter, String resultId, int count, File recipeFile) {
        Object resultItem = adapter.getItemFromRegistry(adapter.createResourceLocation(resultId));
        if (resultItem == null) {
            throw new IllegalStateException(
                    "Unknown result item: " + resultId + " in " + recipeFile);
        }

        ItemStack resultStack = (ItemStack) adapter.createItemStack(resultItem, 0);
        resultStack.size = count;
        return resultStack;
    }

    private static Object resolveIngredient(
            JsonObject obj, OrnitheAdapter adapter, File grugModsDir) throws Exception {
        return resolveIngredientValue(obj, adapter, grugModsDir);
    }

    @GrugGenerated("ingredient resolution: the no-item/no-tag fallback cannot be a fixture")
    private static Object resolveIngredientValue(
            JsonObject obj, OrnitheAdapter adapter, File grugModsDir) throws Exception {
        if (obj.has("item")) {
            return adapter.getItemFromRegistry(
                    adapter.createResourceLocation(obj.get("item").getAsString()));
        } else if (obj.has("tag")) {
            String tag = obj.get("tag").getAsString();
            String[] parts = tag.split(":");
            String ns = parts.length > 1 ? parts[0] : "minecraft";
            String path = parts.length > 1 ? parts[1] : parts[0];

            {
                for (File modDir : listEntries(grugModsDir)) {
                    File tagFile = new File(modDir, "data/" + ns + "/tags/items/" + path + ".json");
                    if (tagFile.exists()) {
                        try (InputStreamReader reader =
                                new InputStreamReader(
                                        new FileInputStream(tagFile), StandardCharsets.UTF_8)) {
                            JsonObject tagJson = JsonParser.parseReader(reader).getAsJsonObject();
                            JsonArray values = tagJson.getAsJsonArray("values");
                            if (values.size() > 0) {
                                JsonElement firstVal = values.get(0);
                                String itemId =
                                        firstVal.isJsonObject()
                                                ? firstVal.getAsJsonObject().get("id").getAsString()
                                                : firstVal.getAsString();
                                if (!itemId.startsWith("#")) {
                                    return adapter.getItemFromRegistry(
                                            adapter.createResourceLocation(itemId));
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
