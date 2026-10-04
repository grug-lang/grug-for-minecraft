package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Covers the bookkeeping around a block entity's render pass: recording, order and nesting. */
class GrugRenderPassTest {

    @AfterEach
    void closeAnyPassLeftOpen() {
        // The nested-open test throws on purpose, which leaves the flag set. Leaving it set would
        // fail every later test in this class for a reason that has nothing to do with them.
        if (GrugRenderPass.isOpen()) {
            GrugRenderPass.close();
        }
    }

    @Test
    void aPassIsNotOpenUntilOneIsOpened() {
        assertFalse(GrugRenderPass.isOpen());
    }

    @Test
    void recordingOutsideAPassIsRefused() {
        // Nothing is recorded, so the caller's report is all that happens: a draw call outside a
        // render pass has nowhere to go.
        assertFalse(GrugRenderPass.record(0, 0, 0, 1, 1, 1));
    }

    @Test
    void anOpenPassIsOpen() {
        GrugRenderPass.open();
        assertTrue(GrugRenderPass.isOpen());
    }

    @Test
    void recordingInsideAPassIsAccepted() {
        GrugRenderPass.open();

        assertTrue(GrugRenderPass.record(0.25, 0.25, 0.25, 0.75, 0.75, 0.75));
    }

    @Test
    void anOpenPassRecordsWhatWasDrawn() {
        GrugRenderPass.open();
        GrugRenderPass.record(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

        assertEquals(
                List.of(new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)),
                GrugRenderPass.recorded());
    }

    @Test
    void aClosedPassIsClosedAgain() {
        GrugRenderPass.open();
        GrugRenderPass.close();

        assertFalse(GrugRenderPass.isOpen());
    }

    @Test
    void recordingAfterClosingIsRefusedAgain() {
        GrugRenderPass.open();
        GrugRenderPass.close();

        assertFalse(GrugRenderPass.record(0, 0, 0, 1, 1, 1));
    }

    @Test
    void aPassReturnsItsBoxesInTheOrderTheyWereDrawn() {
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);
        GrugRenderPass.record(0.5, 0.5, 0.5, 0.75, 0.75, 0.75);

        assertEquals(
                List.of(
                        new GrugBox(0, 0, 0, 1, 1, 1),
                        new GrugBox(0.5, 0.5, 0.5, 0.75, 0.75, 0.75)),
                GrugRenderPass.close());
    }

    @Test
    void anEmptyPassReturnsNothing() {
        GrugRenderPass.open();

        assertEquals(List.of(), GrugRenderPass.close());
    }

    @Test
    void closingHandsTheBoxesOverRatherThanAliasingThePass() {
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);
        List<GrugBox> first = GrugRenderPass.close();

        GrugRenderPass.open();
        GrugRenderPass.record(0.5, 0, 0, 1, 1, 1);

        assertEquals(1, first.size(), "the next pass cannot change a closed one's boxes");
        assertEquals(
                List.of(new GrugBox(0.5, 0, 0, 1, 1, 1)),
                GrugRenderPass.recorded(),
                "the new pass records on its own");
    }

    @Test
    void aPassStartsEmptyRatherThanInheritingWhatALeftOneRecorded() {
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);
        GrugRenderPass.close();

        GrugRenderPass.open();
        assertEquals(List.of(), GrugRenderPass.recorded());
    }

    @Test
    void theRecordedBoxesCannotBeChangedThroughTheReturnedList() {
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);

        assertThrows(
                UnsupportedOperationException.class,
                () -> GrugRenderPass.recorded().add(new GrugBox(0, 0, 0, 1, 1, 1)));
    }

    @Test
    void openingWhileAPassIsOpenBreaksAnInvariant() {
        GrugRenderPass.open();

        // Only the engine can reach this: a script cannot start a loader's render pass, and a
        // loader opens and closes one within a single call. A second open means the engine is
        // broken, and one crash beats geometry quietly drawing into the wrong buffer.
        RuntimeException error = assertThrows(RuntimeException.class, GrugRenderPass::open);
        assertTrue(error.getMessage().contains("already open"), error.getMessage());
    }

    @Test
    void aBoxCarriesTheCornersItWasDrawnWith() {
        GrugBox box = new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);

        assertEquals(0.1, box.x1());
        assertEquals(0.2, box.y1());
        assertEquals(0.3, box.z1());
        assertEquals(0.4, box.x2());
        assertEquals(0.5, box.y2());
        assertEquals(0.6, box.z2());
    }

    @Test
    void aBoxNamesItsCornersInItsTextForm() {
        // Read when a loader or a log line says what was drawn, so the corners have to be legible
        // rather than an opaque handle.
        assertEquals(
                "GrugBox[0.1, 0.2, 0.3 -> 0.4, 0.5, 0.6]",
                new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6).toString());
    }

    @Test
    void twoBoxesAreEqualWhenTheirCornersAre() {
        GrugBox box = new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);

        assertEquals(box, new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6));
        assertEquals(box.hashCode(), new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6).hashCode());
    }

    @Test
    void aBoxIsNotEqualToADifferentOne() {
        GrugBox box = new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);

        assertNotEquals(box, new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.7));
        assertNotEquals(box, new GrugBox(0.2, 0.2, 0.3, 0.4, 0.5, 0.6));
        assertNotEquals(box, new GrugBox(0.1, 0.3, 0.3, 0.4, 0.5, 0.6));
        assertNotEquals(box, new GrugBox(0.1, 0.2, 0.4, 0.4, 0.5, 0.6));
        assertNotEquals(box, new GrugBox(0.1, 0.2, 0.3, 0.5, 0.5, 0.6));
        assertNotEquals(box, new GrugBox(0.1, 0.2, 0.3, 0.4, 0.6, 0.6));
    }

    @Test
    void aBoxIsNotEqualToSomethingElse() {
        assertNotEquals(new GrugBox(0, 0, 0, 1, 1, 1), "not a box");
        assertNotEquals(new GrugBox(0, 0, 0, 1, 1, 1), null);
        assertEquals(new GrugBox(0, 0, 0, 1, 1, 1), new GrugBox(0, 0, 0, 1, 1, 1));
    }

    @Test
    void aBoxIsEqualToItself() {
        // The identity shortcut in equals is the first thing it tries, so comparing a box with the
        // box it already is has to come out equal before any corner is read.
        GrugBox box = new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

        assertEquals(box, box);
        assertEquals(box.hashCode(), box.hashCode());
    }
}
