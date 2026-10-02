package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugModFidelity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Covers which fidelity the running test is judged by, which is what decides whether a screenshot
 * assertion may allow a tolerance. See #77.
 */
class GrugFidelityTest {

    @TempDir Path mods;

    @BeforeEach
    void resetState() {
        Grug.currentTestMod = null;
        Grug.testFidelityOverride = null;
    }

    @AfterEach
    void clearFidelities() {
        GrugModFidelity.load(mods.toFile());
        Grug.currentTestMod = null;
        Grug.testFidelityOverride = null;
    }

    private void declare(String mod, String fidelity) throws IOException {
        Path path = mods.resolve(mod);
        Files.createDirectories(path);
        Files.writeString(
                path.resolve("about.json"),
                "{\"recreation\": {\"fidelity\": \"" + fidelity + "\"}}");
    }

    @Test
    void anExactModMayNotAllowATolerance() throws IOException {
        declare("exactmod", "exact");
        GrugModFidelity.load(mods.toFile());
        Grug.currentTestMod = "exactmod";

        assertTrue(Grug.currentTestIsExact());
        assertFalse(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void anExactModMayStillComparePixelExactly() throws IOException {
        declare("exactmod", "exact");
        GrugModFidelity.load(mods.toFile());
        Grug.currentTestMod = "exactmod";

        assertTrue(GrugModFidelity.allowsTolerance(0));
    }

    @Test
    void aModPromisingLessThanExactMayAllowATolerance() throws IOException {
        declare("behavioralmod", "behavioral");
        declare("inspiredmod", "inspired");
        GrugModFidelity.load(mods.toFile());

        Grug.currentTestMod = "behavioralmod";
        assertFalse(Grug.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));

        Grug.currentTestMod = "inspiredmod";
        assertFalse(Grug.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void aModDeclaringNoFidelityMayAllowATolerance() {
        Grug.currentTestMod = "plainmod";
        assertFalse(Grug.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void withNoTestRunningThereIsNoModToHoldToAFidelity() {
        assertFalse(Grug.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void anOverrideJudgesTheRunningTestInsteadOfItsDeclaredFidelity() throws IOException {
        // The coverage mod is not itself an exact recreation, so it has to be able to stand
        // in as one to cover the rule.
        declare("behavioralmod", "behavioral");
        GrugModFidelity.load(mods.toFile());
        Grug.currentTestMod = "behavioralmod";
        assertTrue(GrugModFidelity.allowsTolerance(1));

        Grug.testFidelityOverride = "exact";
        assertTrue(Grug.currentTestIsExact());
        assertFalse(GrugModFidelity.allowsTolerance(1));

        Grug.testFidelityOverride = "inspired";
        assertFalse(Grug.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void anOverrideCannotLoosenADeclaredExact() throws IOException {
        // The label is a promise about the mod, so a test-only override can tighten the rule but
        // never relax it: an exact mod stays exact whatever its tests ask to be judged as.
        declare("exactmod", "exact");
        GrugModFidelity.load(mods.toFile());
        Grug.currentTestMod = "exactmod";

        Grug.testFidelityOverride = "inspired";
        assertTrue(Grug.currentTestIsExact());
        assertFalse(GrugModFidelity.allowsTolerance(1));

        Grug.testFidelityOverride = "behavioral";
        assertTrue(Grug.currentTestIsExact());
    }
}
