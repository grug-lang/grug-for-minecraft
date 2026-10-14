package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugExactPorts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Covers which rule the running test is judged by, which is what decides whether a screenshot
 * assertion may allow a tolerance: only a port with no deviations may not. See #77.
 */
class GrugExactPortToleranceTest {

    @TempDir Path mods;

    @BeforeEach
    void resetState() {
        GrugTestState.currentTestMod = null;
    }

    @AfterEach
    void clearPorts() {
        GrugExactPorts.load(mods.toFile());
        GrugTestState.currentTestMod = null;
    }

    /**
     * Writes a port's about.json; a null {@code deviations} leaves the key out, as a finished one.
     */
    private void declare(String mod, String deviations) throws IOException {
        Path path = mods.resolve(mod);
        Files.createDirectories(path);
        String key = deviations == null ? "" : ", \"deviations\": " + deviations;
        Files.writeString(
                path.resolve("about.json"),
                "{\"derived_from\": [{\"artifacts\": [{}]" + key + "}]}");
    }

    @Test
    void anExactPortMayNotAllowATolerance() throws IOException {
        declare("exactmod", null);
        GrugExactPorts.load(mods.toFile());
        GrugTestState.currentTestMod = "exactmod";

        assertTrue(GrugTestState.currentTestIsExactPort());
        assertFalse(GrugExactPorts.allowsTolerance(1));
    }

    @Test
    void anExactPortMayStillComparePixelExactly() throws IOException {
        declare("exactmod", null);
        GrugExactPorts.load(mods.toFile());
        GrugTestState.currentTestMod = "exactmod";

        assertTrue(GrugExactPorts.allowsTolerance(0));
    }

    @Test
    void aPortThatStillListsDeviationsMayAllowATolerance() throws IOException {
        declare("unfinishedmod", "[{\"what\": \"x\", \"why\": \"y\", \"tracking_issue\": 1}]");
        GrugExactPorts.load(mods.toFile());

        GrugTestState.currentTestMod = "unfinishedmod";
        assertFalse(GrugTestState.currentTestIsExactPort());
        assertTrue(GrugExactPorts.allowsTolerance(1));
    }

    @Test
    void aModThatIsNotAPortMayAllowATolerance() {
        GrugTestState.currentTestMod = "plainmod";
        assertFalse(GrugTestState.currentTestIsExactPort());
        assertTrue(GrugExactPorts.allowsTolerance(1));
    }

    @Test
    void withNoTestRunningThereIsNoModToHold() {
        assertFalse(GrugTestState.currentTestIsExactPort());
        assertTrue(GrugExactPorts.allowsTolerance(1));
    }

    @Test
    void refusesAnyToleranceFromAnExactPortNamingTheMod() throws IOException {
        declare("exactmod", null);
        GrugExactPorts.load(mods.toFile());
        GrugTestState.currentTestMod = "exactmod";

        assertTrue(GrugTestState.checkTolerance(1).startsWith("exactmod is an exact port"));
        assertNull(GrugTestState.checkTolerance(0));
    }

    @Test
    void acceptsAWholePercentageForAModThatMayAllowOne() {
        GrugTestState.currentTestMod = "plainmod";
        assertNull(GrugTestState.checkTolerance(1));
        assertNull(GrugTestState.checkTolerance(100));
    }

    @Test
    void rejectsAPercentageOutOfRangeOrFractionalBeforeLookingAtThePort() throws IOException {
        declare("exactmod", null);
        GrugExactPorts.load(mods.toFile());
        GrugTestState.currentTestMod = "exactmod";

        assertTrue(GrugTestState.checkTolerance(101).contains("whole number between 0 and 100"));
        assertTrue(GrugTestState.checkTolerance(-1).contains("whole number between 0 and 100"));
        assertTrue(GrugTestState.checkTolerance(1.5).contains("whole number between 0 and 100"));
    }
}
