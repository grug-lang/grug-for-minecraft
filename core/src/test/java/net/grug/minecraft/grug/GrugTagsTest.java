package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugTags.Resolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Covers turning a recipe's tag ingredient into the one item that fills it. */
class GrugTagsTest {

    @TempDir Path mods;

    private void tag(String relativePath, String content) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private Resolution firstItem(String tagId) {
        return GrugTags.firstItem(mods.toFile(), tagId);
    }

    private void assertProblem(String tagId, String problem) {
        Resolution resolution = firstItem(tagId);
        assertFalse(resolution.resolved(), "expected no item for " + tagId);
        assertTrue(resolution.problem().contains(problem), resolution.problem());
        assertNull(resolution.itemId());
    }

    @Test
    void takesTheFirstItemOfATag() throws IOException {
        tag("mymod/data/grug/tags/items/planks.json", "{\"values\": [\"minecraft:oak_planks\"]}");

        assertEquals("minecraft:oak_planks", firstItem("grug:planks").itemId());
    }

    @Test
    void takesTheTagFromWhicheverModShipsIt() throws IOException {
        tag("zzmod/data/grug/tags/items/planks.json", "{\"values\": [\"minecraft:oak_planks\"]}");

        assertEquals("minecraft:oak_planks", firstItem("grug:planks").itemId());
    }

    @Test
    void readsTheObjectFormOfAValue() throws IOException {
        tag(
                "mymod/data/grug/tags/items/planks.json",
                "{\"values\": [{\"id\": \"minecraft:oak_planks\", \"required\": false}]}");

        assertEquals("minecraft:oak_planks", firstItem("grug:planks").itemId());
    }

    @Test
    void readsAPathWithNoNamespaceAsMinecraft() throws IOException {
        tag(
                "mymod/data/minecraft/tags/items/planks.json",
                "{\"values\": [\"minecraft:oak_planks\"]}");

        assertEquals("minecraft:oak_planks", firstItem("planks").itemId());
    }

    @Test
    void followsATagThatNamesATag() throws IOException {
        tag("mymod/data/grug/tags/items/gears.json", "{\"values\": [\"#grug:wooden\"]}");
        tag("mymod/data/grug/tags/items/wooden.json", "{\"values\": [\"grug:wooden_gear\"]}");

        assertEquals("grug:wooden_gear", firstItem("grug:gears").itemId());
    }

    @Test
    void followsTheOptionalTagFormToo() throws IOException {
        tag(
                "mymod/data/grug/tags/items/gears.json",
                "{\"values\": [{\"id\": \"#grug:wooden\", \"required\": false}]}");
        tag("mymod/data/grug/tags/items/wooden.json", "{\"values\": [\"grug:wooden_gear\"]}");

        assertEquals("grug:wooden_gear", firstItem("grug:gears").itemId());
    }

    @Test
    void readsAnObjectValueThatSaysNothingAboutBeingRequired() throws IOException {
        tag(
                "mymod/data/grug/tags/items/planks.json",
                "{\"values\": [{\"id\": \"minecraft:oak_planks\"}]}");

        assertEquals("minecraft:oak_planks", firstItem("grug:planks").itemId());
    }

    @Test
    void treatsAnObjectValueSayingRequiredAsRequired() throws IOException {
        tag(
                "mymod/data/grug/tags/items/gears.json",
                "{\"values\": [{\"id\": \"#grug:missing\", \"required\": true}]}");

        assertProblem("grug:gears", "no mod ships a tag file for 'grug:missing'");
    }

    @Test
    void skipsAnOptionalValueNoModShips() throws IOException {
        tag(
                "mymod/data/grug/tags/items/gears.json",
                "{\"values\": [{\"id\": \"#grug:stone\", \"required\": false},"
                        + " {\"id\": \"#grug:wooden\", \"required\": false}]}");
        tag("mymod/data/grug/tags/items/wooden.json", "{\"values\": [\"grug:wooden_gear\"]}");

        assertEquals("grug:wooden_gear", firstItem("grug:gears").itemId());
    }

    @Test
    void reportsATagWhoseOnlyValuesAreOptionalTagsNoModShips() throws IOException {
        tag(
                "mymod/data/grug/tags/items/gears.json",
                "{\"values\": [{\"id\": \"#grug:stone\", \"required\": false}]}");

        assertProblem("grug:gears", "every value of the tag 'grug:gears' is an optional tag");
    }

    @Test
    void followsACycleOfTagsAsFarAsItGoes() throws IOException {
        tag("mymod/data/grug/tags/items/a.json", "{\"values\": [\"#grug:b\"]}");
        tag("mymod/data/grug/tags/items/b.json", "{\"values\": [\"#grug:a\"]}");

        assertProblem("grug:a", "names tags more than 8 deep");
    }

    @Test
    void reportsATagNoModShips() {
        assertProblem("grug:planks", "no mod ships a tag file for 'grug:planks'");
    }

    @Test
    void reportsATagWithNoValues() throws IOException {
        tag("mymod/data/grug/tags/items/empty.json", "{\"values\": []}");

        assertProblem("grug:empty", "names no items");
    }

    @Test
    void reportsATagWithNoValuesArray() throws IOException {
        tag("mymod/data/grug/tags/items/novalues.json", "{\"replace\": true}");

        assertProblem("grug:novalues", "names no 'values' array");
    }

    @Test
    void reportsValuesThatAreNotAnArray() throws IOException {
        tag("mymod/data/grug/tags/items/object.json", "{\"values\": {}}");

        assertProblem("grug:object", "names no 'values' array");
    }

    @Test
    void reportsATagFileThatIsNotAJsonObject() throws IOException {
        tag("mymod/data/grug/tags/items/list.json", "[]");

        assertProblem("grug:list", "is not a JSON object");
    }

    @Test
    void reportsATagFileThatDoesNotParse() throws IOException {
        tag("mymod/data/grug/tags/items/broken.json", "{ not json");

        assertProblem("grug:broken", "is not readable JSON");
    }

    @Test
    void reportsATagFileThatCannotBeOpened() throws IOException {
        // A tag path that is a directory is as unreadable as one that is not JSON, and it fails
        // before the parser is ever handed anything.
        Files.createDirectories(mods.resolve("mymod/data/grug/tags/items/dir.json"));

        assertProblem("grug:dir", "is not readable JSON");
    }

    @Test
    void reportsATagValueThatIsNeitherAnItemNorATag() throws IOException {
        tag("mymod/data/grug/tags/items/odd.json", "{\"values\": [7]}");

        assertProblem("grug:odd", "a value of the tag 'grug:odd' is neither an item nor a tag");
    }

    @Test
    void reportsATagValueThatNamesNoId() throws IOException {
        tag("mymod/data/grug/tags/items/required.json", "{\"values\": [{\"required\": true}]}");

        assertProblem(
                "grug:required", "a value of the tag 'grug:required' is neither an item nor a tag");
    }

    @Test
    void reportsATagThatNamesAnEmptyId() throws IOException {
        tag("mymod/data/grug/tags/items/blank.json", "{\"values\": [\"\"]}");

        assertProblem("grug:blank", "names an empty item id");
    }

    @Test
    void reportsATagValueWhoseIdIsNull() throws IOException {
        tag("mymod/data/grug/tags/items/nullid.json", "{\"values\": [{\"id\": null}]}");

        assertProblem("grug:nullid", "names an id that is not a string");
    }

    @Test
    void reportsATagValueWhoseIdIsANumber() throws IOException {
        tag("mymod/data/grug/tags/items/numberid.json", "{\"values\": [{\"id\": 5}]}");

        assertProblem("grug:numberid", "names an id that is not a string");
    }

    @Test
    void treatsANonPrimitiveRequiredFlagAsRequired() throws IOException {
        tag(
                "mymod/data/grug/tags/items/oddrequired.json",
                "{\"values\": [{\"id\": \"#grug:missing\", \"required\": {}}]}");

        assertProblem("grug:oddrequired", "no mod ships a tag file for 'grug:missing'");
    }

    @Test
    void reportsTheNestedTagsProblemWhenThereIsOne() throws IOException {
        tag("mymod/data/grug/tags/items/gears.json", "{\"values\": [\"#grug:missing\"]}");

        assertProblem("grug:gears", "no mod ships a tag file for 'grug:missing'");
    }

    @Test
    void splitsAResourceId() {
        assertEquals("minecraft", GrugTags.split("planks")[0]);
        assertEquals("planks", GrugTags.split("planks")[1]);
        assertEquals("grug", GrugTags.split("grug:blocks/planks")[0]);
        assertEquals("blocks/planks", GrugTags.split("grug:blocks/planks")[1]);
        assertEquals("grug", GrugTags.split("#grug:planks")[0]);
        assertEquals("planks", GrugTags.split("#grug:planks")[1]);
    }

    @Test
    void describesWhatItResolved() {
        assertEquals("item minecraft:oak_planks", Resolution.of("minecraft:oak_planks").toString());
        assertEquals("no reason", Resolution.problem("no reason").toString());
    }

    @Test
    void resolvesNothingForATreeThatIsNotThere() {
        assertFalse(firstItem("grug:planks").resolved());
        assertTrue(firstItem("grug:planks").problem().contains("no mod ships"));
    }
}
