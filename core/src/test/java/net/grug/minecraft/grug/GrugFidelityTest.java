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
        GrugTestState.currentTestMod = null;
        GrugTestState.testFidelityOverride = null;
    }

    @AfterEach
    void clearFidelities() {
        GrugModFidelity.load(mods.toFile());
        GrugTestState.currentTestMod = null;
        GrugTestState.testFidelityOverride = null;
    }

    private void declare(String mod, String fidelity) throws IOException {
        Path path = mods.resolve(mod);
        Files.createDirectories(path);
        Files.writeString(
                path.resolve("about.json"),
                "{\"derived_from\": [{\"fidelity\": \"" + fidelity + "\"}]}");
    }

    @Test
    void anExactModMayNotAllowATolerance() throws IOException {
        declare("exactmod", "exact");
        GrugModFidelity.load(mods.toFile());
        GrugTestState.currentTestMod = "exactmod";

        assertTrue(GrugTestState.currentTestIsExact());
        assertFalse(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void anExactModMayStillComparePixelExactly() throws IOException {
        declare("exactmod", "exact");
        GrugModFidelity.load(mods.toFile());
        GrugTestState.currentTestMod = "exactmod";

        assertTrue(GrugModFidelity.allowsTolerance(0));
    }

    @Test
    void aModPromisingLessThanExactMayAllowATolerance() throws IOException {
        declare("behavioralmod", "behavioral");
        GrugModFidelity.load(mods.toFile());

        GrugTestState.currentTestMod = "behavioralmod";
        assertFalse(GrugTestState.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void aModDeclaringNoFidelityMayAllowATolerance() {
        GrugTestState.currentTestMod = "plainmod";
        assertFalse(GrugTestState.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void withNoTestRunningThereIsNoModToHoldToAFidelity() {
        assertFalse(GrugTestState.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void anOverrideJudgesTheRunningTestInsteadOfItsDeclaredFidelity() throws IOException {
        // The coverage mod is not itself an exact recreation, so it has to be able to stand
        // in as one to cover the rule.
        declare("behavioralmod", "behavioral");
        GrugModFidelity.load(mods.toFile());
        GrugTestState.currentTestMod = "behavioralmod";
        assertTrue(GrugModFidelity.allowsTolerance(1));

        GrugTestState.testFidelityOverride = "exact";
        assertTrue(GrugTestState.currentTestIsExact());
        assertFalse(GrugModFidelity.allowsTolerance(1));

        GrugTestState.testFidelityOverride = "behavioral";
        assertFalse(GrugTestState.currentTestIsExact());
        assertTrue(GrugModFidelity.allowsTolerance(1));
    }

    @Test
    void anOverrideCannotLoosenADeclaredExact() throws IOException {
        // The label is a promise about the mod, so a test-only override can tighten the rule but
        // never relax it: an exact mod stays exact whatever its tests ask to be judged as.
        declare("exactmod", "exact");
        GrugModFidelity.load(mods.toFile());
        GrugTestState.currentTestMod = "exactmod";

        GrugTestState.testFidelityOverride = "behavioral";
        assertTrue(GrugTestState.currentTestIsExact());
        assertFalse(GrugModFidelity.allowsTolerance(1));
    }
}
