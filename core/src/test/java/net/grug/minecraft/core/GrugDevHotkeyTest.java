package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the dev hotkey's decision in {@link GrugDevHotkey}. The key press itself cannot be made
 * from a grug test or a unit test, so this decision is the proof: a press may start a run only on a
 * client that owns a local server, because a client-only run on a joined client can never finish
 * and would leave the window it took behind (#1271).
 */
class GrugDevHotkeyTest {

    @Test
    void aPressStartsALocalRunOnlyOnAClientThatOwnsItsWorld() {
        assertTrue(
                GrugDevHotkey.startsLocalRun(true),
                "a single-player client runs the tests against its own world");

        assertFalse(
                GrugDevHotkey.startsLocalRun(false),
                "a client joined to a server has no integrated server to advance the run on");
    }
}
