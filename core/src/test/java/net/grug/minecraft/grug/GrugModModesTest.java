package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Covers the mode each mod's code runs in, from its about.json and the player's grant (#334). */
class GrugModModesTest {

    @TempDir Path tmp;

    private Path game() throws IOException {
        return Files.createDirectories(tmp.resolve("game"));
    }

    private Path mods() throws IOException {
        return Files.createDirectories(game().resolve("grug_mods"));
    }

    private void about(String mod, String json) throws IOException {
        Files.writeString(Files.createDirectories(mods().resolve(mod)).resolve("about.json"), json);
    }

    /** The state file the mod manager writes, which is where a grant lives. */
    private void state(String json) throws IOException {
        Files.writeString(game().resolve(GrugModManager.STATE), json);
    }

    private void stateGranting(String mod, boolean granted) throws IOException {
        state(
                "{\"mods\": {\""
                        + mod
                        + "\": {\"on\": true, \"unsafe_mode_granted\": "
                        + granted
                        + "}}}");
    }

    private void load() throws IOException {
        GrugModModes.load(mods().toFile(), game().toFile());
    }

    @Test
    void aModThatAsksAndWhosePlayerAcceptedRunsUnsafe() {
        assertTrue(GrugModModes.unsafeMode(true, true));
    }

    @Test
    void aModThatAsksWithoutThePlayersGrantRunsSafe() {
        assertFalse(GrugModModes.unsafeMode(true, false));
    }

    @Test
    void anUpdateThatReplacedTheAcceptedFilesClearsTheGrant() {
        // What a mod is left with when the manager clears its grant for an update: it still asks,
        // and runs safe until the player accepts the new files.
        assertFalse(GrugModModes.unsafeMode(true, false));
    }

    @Test
    void aGrantWithoutARequestIsNotAMode() {
        // Marking a mod unsafe by hand is the mod manager's own setting, not a request it made.
        assertFalse(GrugModModes.unsafeMode(false, true));
    }

    @Test
    void aModThatNeitherAsksNorIsGrantedRunsSafe() {
        assertFalse(GrugModModes.unsafeMode(false, false));
    }

    @Test
    void aModThatAsksAndWasGrantedRunsUnsafe() throws IOException {
        about("mymod", "{\"requests_unsafe_mode\": true}");
        stateGranting("mymod", true);

        load();

        assertTrue(GrugModModes.requestsUnsafeMode("mymod"));
        assertTrue(GrugModModes.granted("mymod"));
        assertTrue(GrugModModes.unsafe("mymod"));
        assertEquals(Map.of("mymod", true), GrugModModes.unsafeModes());
    }

    @Test
    void aModThatAsksAndWasNotGrantedRunsSafe() throws IOException {
        about("mymod", "{\"requests_unsafe_mode\": true}");
        stateGranting("mymod", false);

        load();

        assertTrue(GrugModModes.requestsUnsafeMode("mymod"));
        assertFalse(GrugModModes.granted("mymod"));
        assertFalse(GrugModModes.unsafe("mymod"));
    }

    @Test
    void aModWithoutTheKeyDoesNotRequestUnsafeMode() throws IOException {
        // Absent means false, even for a player who granted it for some other reason.
        about("mymod", "{\"name\": \"Test\"}");
        stateGranting("mymod", true);

        load();

        assertFalse(GrugModModes.requestsUnsafeMode("mymod"));
        assertTrue(GrugModModes.granted("mymod"));
        assertFalse(GrugModModes.unsafe("mymod"));
    }

    @Test
    void aModThatDeclinesUnsafeModeRunsSafe() throws IOException {
        about("mymod", "{\"requests_unsafe_mode\": false}");
        stateGranting("mymod", true);

        load();

        assertFalse(GrugModModes.requestsUnsafeMode("mymod"));
        assertFalse(GrugModModes.unsafe("mymod"));
    }

    @Test
    void aRequestThatIsNotABooleanReadsAsNoRequest() throws IOException {
        // about_schema.py refuses these; a game that runs a mod nobody validated reads the unclear
        // request as no request, which is the safe side of the answer.
        for (String value : new String[] {"\"true\"", "1", "null", "{}", "[]"}) {
            about("mymod", "{\"requests_unsafe_mode\": " + value + "}");
            stateGranting("mymod", true);

            load();

            assertFalse(GrugModModes.requestsUnsafeMode("mymod"), value);
            assertFalse(GrugModModes.unsafe("mymod"), value);
        }
    }

    @Test
    void aMalformedAboutJsonReadsAsNoRequest() throws IOException {
        about("mymod", "{");

        load();

        assertFalse(GrugModModes.requestsUnsafeMode("mymod"));
        assertFalse(GrugModModes.unsafe("mymod"));
    }

    @Test
    void aModWithoutAnAboutJsonDoesNotRequestUnsafeMode() throws IOException {
        Files.createDirectories(mods().resolve("mymod/code"));

        load();

        assertFalse(GrugModModes.requestsUnsafeMode("mymod"));
        assertFalse(GrugModModes.unsafe("mymod"));
    }

    @Test
    void aRunWithNoGameDirectoryReadsEveryModAsSafe() throws IOException {
        // A reference run stages mods in a directory of its own and has no player to grant one.
        about("mymod", "{\"requests_unsafe_mode\": true}");

        GrugModModes.load(mods().toFile(), null);

        assertFalse(GrugModModes.granted("mymod"));
        assertFalse(GrugModModes.unsafe("mymod"));
    }

    @Test
    void aFileInTheModsDirectoryIsNotAMod() throws IOException {
        Files.writeString(mods().resolve("mod_api.json"), "{}");

        load();

        assertTrue(GrugModModes.unsafeModes().isEmpty());
    }

    @Test
    void loadingAgainForgetsTheModsThatAreGone() throws IOException {
        about("mymod", "{\"requests_unsafe_mode\": true}");
        stateGranting("mymod", true);
        load();
        assertTrue(GrugModModes.unsafe("mymod"));

        GrugModModes.load(new File("missing-mods-directory"), game().toFile());

        assertFalse(GrugModModes.unsafe("mymod"));
        assertFalse(GrugModModes.requestsUnsafeMode("mymod"));
        // The grant is the player's record of the mod and stays in the state, but no file of the
        // mod compiles, so there is no mode for it to run in.
        assertTrue(GrugModModes.granted("mymod"));
        assertTrue(GrugModModes.unsafeModes().isEmpty());
    }
}
