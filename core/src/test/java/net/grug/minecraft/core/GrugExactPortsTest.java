package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Covers which mods are exact ports, as {@link GrugExactPorts} reads it at startup. */
class GrugExactPortsTest {

    @TempDir Path mods;

    private void about(String mod, String json) throws IOException {
        Path path = mods.resolve(mod).resolve("about.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
    }

    private void load() {
        GrugExactPorts.load(mods.toFile());
    }

    @Test
    void aPortWithNoDeviationsIsExact() throws IOException {
        about("noarray", "{\"derived_from\": [{\"artifacts\": []}]}");
        about("emptyarray", "{\"derived_from\": [{\"artifacts\": [], \"deviations\": []}]}");
        load();

        assertTrue(GrugExactPorts.isExactPort("noarray"));
        assertTrue(GrugExactPorts.isExactPort("emptyarray"));
    }

    @Test
    void aPortThatListsADeviationIsNotExactYet() throws IOException {
        about(
                "unfinished",
                "{\"derived_from\": [{\"artifacts\": [], \"deviations\": [{\"what\": \"x\"}]}]}");
        load();

        assertFalse(GrugExactPorts.isExactPort("unfinished"));
    }

    @Test
    void ignoresTheRestOfTheAboutJson() throws IOException {
        about(
                "mymod",
                "{\"name\": \"mymod\", \"derived_from\": [{\"name\": \"x\", \"artifacts\": []},"
                        + " {\"name\": \"y\"}]}");
        load();

        assertTrue(GrugExactPorts.isExactPort("mymod"));
    }

    @Test
    void aModThatIsNotAPortIsNotExact() throws IOException {
        about("plain", "{\"name\": \"plain\"}");
        about("nosuchfiles", "{\"derived_from\": [{\"name\": \"a source, not a port\"}]}");
        Files.createDirectories(mods.resolve("noabout"));
        load();

        assertFalse(GrugExactPorts.isExactPort("plain"));
        assertFalse(GrugExactPorts.isExactPort("nosuchfiles"));
        assertFalse(GrugExactPorts.isExactPort("noabout"));
        assertFalse(GrugExactPorts.isExactPort("nosuchmod"));
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
        about("artifactsnotarray", "{\"derived_from\": [{\"artifacts\": 1}]}");
        about(
                "deviationsnotarray",
                "{\"derived_from\": [{\"artifacts\": [], \"deviations\": \"none\"}]}");
        load();

        for (String mod :
                new String[] {
                    "malformed",
                    "notanobject",
                    "derivedfromnotarray",
                    "emptyderivedfrom",
                    "entrynotobject",
                    "artifactsnotarray",
                    "deviationsnotarray"
                }) {
            assertFalse(GrugExactPorts.isExactPort(mod), mod);
        }
    }

    @Test
    void treatsAnUnreadableModsDirectoryAsDeclaringNothing() {
        GrugExactPorts.load(new File(mods.toFile(), "does-not-exist"));
        assertFalse(GrugExactPorts.isExactPort("anything"));
    }

    @Test
    void forgetsThePortsThatAreNoLongerExact() throws IOException {
        about("mymod", "{\"derived_from\": [{\"artifacts\": []}]}");
        load();
        about("mymod", "{}");
        load();

        assertFalse(GrugExactPorts.isExactPort("mymod"));
    }
}
