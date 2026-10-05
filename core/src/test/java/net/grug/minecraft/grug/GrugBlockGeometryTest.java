package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers what {@link GrugBlockGeometry} does when a block declared custom rendering but has nothing
 * to draw with.
 *
 * <p>The native side cannot be reached from a JVM test, so these cover the one case that needs no
 * native call: a block entity that was never created, so its handle is zero. Passing that zero
 * handle to the native side would pass a null entity pointer, which is why draw checks it.
 *
 * <p>The report is read out of {@link Grug#runtimeErrorQueue} rather than through a loader adapter,
 * because that is the channel every loader drains into chat as a red message, so it is the one that
 * reaches the player. A report that only reached the log would leave a block rendering as nothing
 * without saying so.
 */
class GrugBlockGeometryTest {

    private final List<String> reported = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void drainQueue() {
        synchronized (Grug.runtimeErrorQueue) {
            Grug.runtimeErrorQueue.clear();
        }
        // draw opens and closes the pass itself, so nothing should be left open. A failing
        // assertion
        // in the middle of one would leave every later test in this class unable to open a pass.
        if (GrugRenderPass.isOpen()) {
            GrugRenderPass.close();
        }
    }

    @Test
    void aBlockEntityThatWasNeverCreatedIsReportedRatherThanCalled() {
        // The zero handle is what a block with no block entity script produces, and it is the only
        // half of the guard a JVM test can reach, since a real handle needs the native side.
        // Passing
        // it on would hand the native code a null entity pointer, so draw has to notice it is zero.
        List<GrugBox> boxes = GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID);

        assertTrue(boxes.isEmpty(), "There is nothing to draw, so no boxes come back.");

        reported.addAll(Grug.runtimeErrorQueue);
        assertEquals(1, reported.size(), "Exactly one report, naming what the author has to fix.");
        assertTrue(
                reported.get(0).contains("set_custom_render"),
                "The report names the call that caused it: " + reported.get(0));
        assertTrue(
                reported.get(0).contains("set_block_entity"),
                "The report names the call that would fix it: " + reported.get(0));
    }

    @Test
    void thePassIsClosedEvenWhenThereWasNothingToRun() {
        GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID);

        assertFalse(
                GrugRenderPass.isOpen(),
                "A pass left open would make the next block's pass fail its nesting invariant.");
    }

    @Test
    void aValidHandleWithNoRenderFunctionIsReportedTheSameWay() {
        // The other way there is nothing to run: a block entity that exists, but whose script has
        // no render function. The handle is non-zero here, so the only thing stopping the call is
        // the function id, and this covers the branch a second condition introduces.
        GrugBlockGeometry.draw(1L, Grug.INVALID_GRUG_EXPORT_FN_ID);

        reported.addAll(Grug.runtimeErrorQueue);
        assertEquals(1, reported.size(), "One report, the same one the missing block entity gets.");
        assertTrue(
                reported.get(0).contains("set_block_entity"),
                "Both ways of having nothing to draw are the same mistake: " + reported.get(0));
    }
}
