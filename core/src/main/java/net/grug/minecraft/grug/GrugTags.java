package net.grug.minecraft.grug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Resolves a recipe's {@code {"tag": "..."}} ingredient to the one item that fills it.
 *
 * <p>A grug recipe has one item per ingredient, because that is what a crafting grid registration
 * takes, so a tag resolves to the first item it names. Which item that is comes from the file, so
 * this class only reads files and stays testable; turning the id it returns into a game item is the
 * loader's job.
 *
 * <p>Tags nest, and a nested tag has a real answer as long as the tag it names ships, so a tag whose
 * first value is {@code "#other:tag"} is followed rather than given up on. A tag file that no mod
 * ships, one with no values, and one whose first value is neither an item nor a tag are all content
 * no item can come out of, so each reports why instead of resolving to nothing.
 */
public final class GrugTags {

    /** How far a chain of tags that name tags may go before it is treated as a cycle. */
    private static final int MAX_DEPTH = 8;

    @GrugGenerated("utility class: never instantiated")
    private GrugTags() {}

    /**
     * The item that fills {@code tagId}, or why no item does.
     *
     * <p>{@link #problem()} is null exactly when {@link #itemId()} is set. The caller composes the
     * message, because only it knows which recipe asked.
     *
     * <p>A class rather than a record because core compiles to Java 8, which the 1.2.5 loader's game
     * JVM needs.
     */
    public static final class Resolution {
        private final String itemId;
        private final String problem;

        private Resolution(String itemId, String problem) {
            this.itemId = itemId;
            this.problem = problem;
        }

        static Resolution of(String itemId) {
            return new Resolution(itemId, null);
        }

        static Resolution problem(String problem) {
            return new Resolution(null, problem);
        }

        public String itemId() {
            return itemId;
        }

        public String problem() {
            return problem;
        }

        public boolean resolved() {
            return itemId != null;
        }

        @Override
        public String toString() {
            return resolved() ? "item " + itemId : problem;
        }
    }

    /**
     * Looks {@code tagId} up in the mods tree. A bare path is read as {@code minecraft:<path>}, which
     * is how a recipe that predates namespacing writes it, and a leading {@code #} is accepted
     * because that is how a tag names another tag.
     */
    public static Resolution firstItem(File modsDir, String tagId) {
        return firstItem(modsDir, tagId, MAX_DEPTH);
    }

    private static Resolution firstItem(File modsDir, String tagId, int depthLeft) {
        String[] parts = split(tagId);

        File tagFile = GrugResourceIndex.findResource(
                modsDir, "data", parts[0], "tags/items/" + parts[1] + ".json");
        if (tagFile == null) {
            return Resolution.problem("no mod ships a tag file for '" + tagId + "'");
        }

        JsonElement parsed;
        try (Reader reader = Files.newBufferedReader(tagFile.toPath(), StandardCharsets.UTF_8)) {
            parsed = JsonParser.parseReader(reader);
        } catch (Exception e) {
            return Resolution.problem(
                    "the tag file for '" + tagId + "' is not readable JSON: " + e.getMessage());
        }
        if (!parsed.isJsonObject()) {
            return Resolution.problem("the tag file for '" + tagId + "' is not a JSON object");
        }
        JsonObject tag = parsed.getAsJsonObject();

        JsonElement valuesElement = tag.get("values");
        if (valuesElement == null || !valuesElement.isJsonArray()) {
            return Resolution.problem("the tag file for '" + tagId + "' names no 'values' array");
        }
        JsonArray values = valuesElement.getAsJsonArray();
        if (values.size() == 0) {
            return Resolution.problem("the tag '" + tagId + "' names no items");
        }

        return firstValue(modsDir, tagId, values.get(0), depthLeft);
    }

    private static Resolution firstValue(
            File modsDir, String tagId, JsonElement value, int depthLeft) {
        String id;
        boolean nested = false;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            id = value.getAsString();
            nested = id.startsWith("#");
            if (nested) {
                id = id.substring(1);
            }
        } else if (value.isJsonObject() && value.getAsJsonObject().has("id")) {
            // The 1.19+ tag form, {"id": "minecraft:diamond"}, which is what the coverage mod ships.
            id = value.getAsJsonObject().get("id").getAsString();
        } else {
            return Resolution.problem(
                    "the first value of the tag '" + tagId + "' is neither an item nor a tag");
        }

        if (id.isEmpty()) {
            return Resolution.problem("the tag '" + tagId + "' names an empty item id");
        }

        if (!nested) {
            return Resolution.of(id);
        }
        if (depthLeft <= 1) {
            return Resolution.problem(
                    "the tag '" + tagId + "' names tags more than " + MAX_DEPTH + " deep");
        }

        Resolution inner = firstItem(modsDir, id, depthLeft - 1);
        if (inner.resolved()) {
            return inner;
        }
        return Resolution.problem(inner.problem());
    }

    /** The namespace and path of a resource id, defaulting a bare path to the minecraft namespace. */
    static String[] split(String resourceId) {
        String trimmed = resourceId.startsWith("#") ? resourceId.substring(1) : resourceId;
        int colon = trimmed.indexOf(':');
        if (colon < 0) return new String[] {"minecraft", trimmed};
        return new String[] {trimmed.substring(0, colon), trimmed.substring(colon + 1)};
    }
}
