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
        about("exactmod", "{\"derived_from\": [{\"fidelity\": \"exact\"}]}");
        about("behavioralmod", "{\"derived_from\": [{\"fidelity\": \"behavioral\"}]}");
        load();

        assertEquals("exact", GrugModFidelity.of("exactmod"));
        assertEquals("behavioral", GrugModFidelity.of("behavioralmod"));
    }

    @Test
    void ignoresTheRestOfTheAboutJson() throws IOException {
        about(
                "mymod",
                "{\"name\": \"mymod\", \"derived_from\": [{\"fidelity\": \"exact\","
                        + " \"deviations\": []}]}");
        load();

        assertEquals("exact", GrugModFidelity.of("mymod"));
    }

    @Test
    void treatsAModWithNoLineageEntryAsPromisingNothing() throws IOException {
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
        about("derivedfromnotarray", "{\"derived_from\": \"exact\"}");
        about("emptyderivedfrom", "{\"derived_from\": []}");
        about("entrynotobject", "{\"derived_from\": [\"exact\"]}");
        about("nofidelity", "{\"derived_from\": [{}]}");
        about("fidelitynotprimitive", "{\"derived_from\": [{\"fidelity\": [\"exact\"]}]}");
        load();

        assertNull(GrugModFidelity.of("malformed"));
        assertNull(GrugModFidelity.of("notanobject"));
        assertNull(GrugModFidelity.of("derivedfromnotarray"));
        assertNull(GrugModFidelity.of("emptyderivedfrom"));
        assertNull(GrugModFidelity.of("entrynotobject"));
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
        about("mymod", "{\"derived_from\": [{\"fidelity\": \"exact\"}]}");
        load();
        about("mymod", "{}");
        load();

        assertNull(GrugModFidelity.of("mymod"));
    }

    @Test
    void onlyExactIsExact() throws IOException {
        about("exactmod", "{\"derived_from\": [{\"fidelity\": \"exact\"}]}");
        about("behavioralmod", "{\"derived_from\": [{\"fidelity\": \"behavioral\"}]}");
        load();

        assertTrue(GrugModFidelity.isExact("exactmod"));
        assertFalse(GrugModFidelity.isExact("behavioralmod"));
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
                        .getAsJsonObject("derived_from")
                        .getAsJsonObject("items")
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
