package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Covers the fidelity each mod declares, as {@link GrugModFidelity} reads it at startup. */
class GrugModFidelityTest {

    @TempDir Path mods;

    private void write(String relativePath, String content) throws IOException {
        Path path = mods.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void about(String mod, String json) throws IOException {
        write(mod + "/about.json", json);
    }

    private void load() {
        GrugModFidelity.load(mods.toFile());
    }

    @Test
    void readsEachModsDeclaredFidelity() throws IOException {
        about("exactmod", "{\"recreation\": {\"fidelity\": \"exact\"}}");
        about("behavioralmod", "{\"recreation\": {\"fidelity\": \"behavioral\"}}");
        about("inspiredmod", "{\"recreation\": {\"fidelity\": \"inspired\"}}");
        load();

        assertEquals("exact", GrugModFidelity.of("exactmod"));
        assertEquals("behavioral", GrugModFidelity.of("behavioralmod"));
        assertEquals("inspired", GrugModFidelity.of("inspiredmod"));
    }

    @Test
    void ignoresTheRestOfTheAboutJson() throws IOException {
        about(
                "mymod",
                "{\"name\": \"mymod\", \"recreation\": {\"fidelity\": \"exact\","
                        + " \"deviations\": []}}");
        load();

        assertEquals("exact", GrugModFidelity.of("mymod"));
    }

    @Test
    void treatsAModWithNoRecreationBlockAsPromisingNothing() throws IOException {
        about("plain", "{\"name\": \"plain\"}");
        load();

        assertNull(GrugModFidelity.of("plain"));
        assertFalse(GrugModFidelity.isExact("plain"));
    }

    @Test
    void treatsAModWithNoAboutJsonAsPromisingNothing() throws IOException {
        write("noabout/LICENSE", "license text");
        load();

        assertNull(GrugModFidelity.of("noabout"));
    }

    @Test
    void treatsAnUnknownModAsPromisingNothing() {
        assertNull(GrugModFidelity.of("nosuchmod"));
        assertFalse(GrugModFidelity.isExact("nosuchmod"));
    }

    @Test
    void toleratesMetadataItCannotUnderstand() throws IOException {
        // about_schema.py is what rejects these. Ignoring them here keeps a hand-broken about.json
        // from being the thing that stops the game starting.
        about("malformed", "{not json at all");
        about("notanobject", "[]");
        about("recreationnotobject", "{\"recreation\": \"exact\"}");
        about("nofidelity", "{\"recreation\": {}}");
        about("fidelitynotprimitive", "{\"recreation\": {\"fidelity\": [\"exact\"]}}");
        load();

        assertNull(GrugModFidelity.of("malformed"));
        assertNull(GrugModFidelity.of("notanobject"));
        assertNull(GrugModFidelity.of("recreationnotobject"));
        assertNull(GrugModFidelity.of("nofidelity"));
        assertNull(GrugModFidelity.of("fidelitynotprimitive"));
    }

    @Test
    void treatsAnUnreadableModsDirectoryAsDeclaringNothing() {
        GrugModFidelity.load(new File(mods.toFile(), "does-not-exist"));
        assertNull(GrugModFidelity.of("anything"));
    }

    @Test
    void forgetsTheFidelitiesOfModsThatNoLongerDeclareOne() throws IOException {
        about("mymod", "{\"recreation\": {\"fidelity\": \"exact\"}}");
        load();
        about("mymod", "{}");
        load();

        assertNull(GrugModFidelity.of("mymod"));
    }

    @Test
    void onlyExactIsExact() throws IOException {
        about("exactmod", "{\"recreation\": {\"fidelity\": \"exact\"}}");
        about("behavioralmod", "{\"recreation\": {\"fidelity\": \"behavioral\"}}");
        about("inspiredmod", "{\"recreation\": {\"fidelity\": \"inspired\"}}");
        load();

        assertTrue(GrugModFidelity.isExact("exactmod"));
        assertFalse(GrugModFidelity.isExact("behavioralmod"));
        assertFalse(GrugModFidelity.isExact("inspiredmod"));
    }

    @Test
    void listsExactlyTheFidelitiesAboutJsonAccepts() throws IOException {
        // The list is what Test.force_fidelity validates against, so read the schema's enum rather
        // than restating it: a new fidelity in about_schema.json then has to be reflected here.
        Path schema = Paths.get("..", "about_schema.json").normalize();
        JsonArray declared =
                JsonParser.parseString(Files.readString(schema))
                        .getAsJsonObject()
                        .getAsJsonObject("properties")
                        .getAsJsonObject("recreation")
                        .getAsJsonObject("properties")
                        .getAsJsonObject("fidelity")
                        .getAsJsonArray("enum");
        List<String> values = new ArrayList<>();
        for (JsonElement value : declared) {
            values.add(value.getAsString());
        }

        assertEquals(values, GrugModFidelity.FIDELITIES);
    }
}
