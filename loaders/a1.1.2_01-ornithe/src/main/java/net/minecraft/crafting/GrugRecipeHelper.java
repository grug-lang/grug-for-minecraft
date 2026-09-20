package net.minecraft.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.grug.minecraft.ornithe.OrnitheAdapter;
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

public class GrugRecipeHelper {
    public static void addShaped(ItemStack output, Object... inputs) {
        CraftingManager.getInstance().registerShaped(output, inputs);
    }

    public static void registerAutoDiscoveredRecipes(File grugModsDir) {
        File[] modDirs = grugModsDir.listFiles(File::isDirectory);
        if (modDirs == null) return;

        OrnitheAdapter adapter = new OrnitheAdapter();

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
                          .forEach(p -> {
                              try {
                                  registerJsonRecipe(p.toFile(), adapter, grugModsDir);
                              } catch (Exception e) {
                                  GrugModLoader.LOGGER.error("Failed to register recipe: " + p, e);
                              }
                          });
                } catch (Exception e) {
                    GrugModLoader.LOGGER.error("Failed to walk recipes directory: " + recipesDir, e);
                }
            }
        }
    }

    private static void registerJsonRecipe(File recipeFile, OrnitheAdapter adapter, File grugModsDir) throws Exception {
        JsonObject json;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(recipeFile), StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }

        String type = json.get("type").getAsString();

        // TODO: Support or warn on shapeless recipes with MC Alpha 1.1.2_01
        // https://github.com/grug-lang/grug-for-minecraft/issues/11
        if (!"minecraft:crafting_shaped".equals(type)) {
            GrugModLoader.LOGGER.warn("Skipping unsupported recipe type (only shaped is supported): " + type + " in " + recipeFile);
            return;
        }

        JsonObject resultObj = json.getAsJsonObject("result");
        String resultId = resultObj.has("id") ? resultObj.get("id").getAsString() : resultObj.get("item").getAsString();
        int count = resultObj.has("count") ? resultObj.get("count").getAsInt() : 1;

        Object resultItem = adapter.getItemFromRegistry(adapter.createResourceLocation(resultId));
        if (resultItem == null) {
            GrugModLoader.LOGGER.warn("Unknown result item: " + resultId + " in " + recipeFile);
            return;
        }

        ItemStack resultStack = (ItemStack) adapter.createItemStack(resultItem);
        resultStack.size = count;

        JsonArray pattern = json.getAsJsonArray("pattern");
        JsonObject keyObj = json.getAsJsonObject("key");

        List<Object> inputs = new ArrayList<>();
        for (JsonElement row : pattern) {
            inputs.add(row.getAsString());
        }

        for (Map.Entry<String, JsonElement> entry : keyObj.entrySet()) {
            inputs.add(entry.getKey().charAt(0));
            Object item = resolveIngredient(entry.getValue().getAsJsonObject(), adapter, grugModsDir);
            if (item == null) {
                GrugModLoader.LOGGER.warn("Unknown ingredient in recipe: " + recipeFile);
                return;
            }
            inputs.add(item);
        }

        addShaped(resultStack, inputs.toArray());
        GrugModLoader.LOGGER.info("Registered shaped recipe for " + resultId);
    }

    private static Object resolveIngredient(JsonObject obj, OrnitheAdapter adapter, File grugModsDir) throws Exception {
        if (obj.has("item")) {
            return adapter.getItemFromRegistry(adapter.createResourceLocation(obj.get("item").getAsString()));
        } else if (obj.has("tag")) {
            String tag = obj.get("tag").getAsString();
            String[] parts = tag.split(":");
            String ns = parts.length > 1 ? parts[0] : "minecraft";
            String path = parts.length > 1 ? parts[1] : parts[0];

            File[] modDirs = grugModsDir.listFiles(File::isDirectory);
            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File tagFile = new File(modDir, "data/" + ns + "/tags/items/" + path + ".json");
                    if (tagFile.exists()) {
                        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(tagFile), StandardCharsets.UTF_8)) {
                            JsonObject tagJson = JsonParser.parseReader(reader).getAsJsonObject();
                            JsonArray values = tagJson.getAsJsonArray("values");
                            if (values.size() > 0) {
                                JsonElement firstVal = values.get(0);
                                String itemId = firstVal.isJsonObject() ? firstVal.getAsJsonObject().get("id").getAsString() : firstVal.getAsString();
                                if (!itemId.startsWith("#")) {
                                    return adapter.getItemFromRegistry(adapter.createResourceLocation(itemId));
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
