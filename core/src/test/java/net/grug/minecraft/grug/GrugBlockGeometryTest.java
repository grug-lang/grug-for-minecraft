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
 * <p>The native side cannot be reached from a JVM test, so these cover the two ways there is
 * nothing to run that need no native call: a block entity that was never created, so its handle is
 * zero, and one that exists but whose script exports no render function. Passing that zero handle
 * to the native side would pass a null entity pointer, which is why draw checks it.
 *
 * <p>The report is read out of {@link Grug#runtimeErrorQueue} rather than through a loader adapter,
 * because that is the channel every loader drains into chat as a red message, so it is the one that
 * reaches the player. A report that only reached the log would leave a block rendering as nothing
 * without saying so. The queue is read under its own monitor, as every drain of it in the tree is.
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
        // assertion in the middle of one would leave every later test in this class unable to
        // open a pass.
        if (GrugRenderPass.isOpen()) {
            GrugRenderPass.close();
        }
    }

    /** Takes what the run reported, which is the message the player would have been shown. */
    private List<String> drainReports() {
        reported.clear();
        synchronized (Grug.runtimeErrorQueue) {
            reported.addAll(Grug.runtimeErrorQueue);
        }
        return reported;
    }

    @Test
    void aBlockEntityThatWasNeverCreatedIsReportedRatherThanCalled() {
        // The zero handle is what a block with no block entity script produces, and it is one of
        // the two halves of the guard a JVM test can reach, since a real handle needs the native
        // side. Passing it on would hand the native code a null entity pointer, so draw has to
        // notice it is zero.
        List<GrugBox> boxes = GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID);

        assertTrue(boxes.isEmpty(), "There is nothing to draw, so no boxes come back.");

        List<String> reports = drainReports();
        assertEquals(1, reports.size(), "Exactly one report, naming what the author has to fix.");
        assertTrue(
                reports.get(0).contains("set_custom_render"),
                "The report names the call that caused it: " + reports.get(0));
        assertTrue(
                reports.get(0).contains("set_block_entity"),
                "The report names the call that would fix it: " + reports.get(0));
    }

    @Test
    void thePassIsClosedEvenWhenThereWasNothingToRun() {
        GrugBlockGeometry.draw(0, Grug.INVALID_GRUG_EXPORT_FN_ID);

        assertFalse(
                GrugRenderPass.isOpen(),
                "A pass left open would make the next block's pass fail its nesting invariant.");
    }

    @Test
    void aBlockEntityWithoutARenderFunctionIsToldToAddOne() {
        // The other way there is nothing to run: a block entity that exists, so its handle is
        // non-zero, but whose script exports no render function. This is a different mistake from a
        // missing block entity and has a different fix, so being told to add a set_block_entity the
        // author already called would be as unhelpful as being told nothing at all.
        List<GrugBox> boxes = GrugBlockGeometry.draw(1L, Grug.INVALID_GRUG_EXPORT_FN_ID);

        assertTrue(boxes.isEmpty(), "There is nothing to draw, so no boxes come back.");

        List<String> reports = drainReports();
        assertEquals(1, reports.size(), "One report, and it is this mistake's own.");
        assertTrue(
                reports.get(0).contains("render()"),
                "The report names the missing export: " + reports.get(0));
        assertFalse(
                reports.get(0).contains("set_block_entity"),
                "The block entity is already there, so suggesting set_block_entity would send the"
                        + " author after a call they have already made: "
                        + reports.get(0));
    }
}
