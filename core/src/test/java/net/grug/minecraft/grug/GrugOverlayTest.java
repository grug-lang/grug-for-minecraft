package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the bookkeeping around one overlay pass: what a render records, what a click takes, and
 * the invariants that keep the two kinds of pass from mixing.
 */
class GrugOverlayTest {

    @BeforeEach
    @AfterEach
    void closeAnyPassLeftOpen() {
        if (GrugOverlay.isRender()) {
            GrugOverlay.closeRender();
        } else if (GrugOverlay.isClick()) {
            GrugOverlay.closeClick();
        }
    }

    @Test
    void aPressStartsUntakenAndOnlyAnOverlayTakeRecordsIt() {
        GrugOverlay.beginTestPress();
        assertFalse(GrugOverlay.testPressTaken(), "A press no overlay takes is not taken.");

        GrugOverlay.noteTestPressTaken();
        assertTrue(GrugOverlay.testPressTaken());

        GrugOverlay.beginTestPress();
        assertFalse(GrugOverlay.testPressTaken(), "The next press starts untaken again.");
    }

    @Test
    void aRenderPassAnswersItsGeometryAndTheDrawsItRecorded() {
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);

        assertEquals(426, GrugOverlay.screenWidth());
        assertEquals(240, GrugOverlay.screenHeight());
        assertEquals(125, GrugOverlay.guiLeft());
        assertEquals(37, GrugOverlay.guiTop());
        assertEquals(176, GrugOverlay.guiWidth());
        assertEquals(166, GrugOverlay.guiHeight());
        assertEquals(300, GrugOverlay.mouseX());
        assertEquals(20, GrugOverlay.mouseY());
        assertTrue(GrugOverlay.isRender());
        assertFalse(GrugOverlay.isClick());

        Object item = new Object();
        assertTrue(GrugOverlay.recordItem(item, 319, 1));
        assertTrue(GrugOverlay.recordBox(318, 0, 336, 18));

        GrugOverlayGeometry geometry = GrugOverlay.closeRender();
        assertEquals(1, geometry.items().size());
        assertEquals(item, geometry.items().get(0).item());
        assertEquals(319, geometry.items().get(0).x());
        assertEquals(1, geometry.items().get(0).y());
        assertEquals(1, geometry.boxes().size());
        assertEquals(318, geometry.boxes().get(0).x1());
        assertEquals(0, geometry.boxes().get(0).y1());
        assertEquals(336, geometry.boxes().get(0).x2());
        assertEquals(18, geometry.boxes().get(0).y2());
        assertFalse(GrugOverlay.isOpen());
    }

    @Test
    void aClickPassAnswersItsButtonAndWhetherThePressWasTaken() {
        GrugOverlay.openClick(426, 240, 125, 37, 176, 166, 300, 20, 2, false);

        assertEquals(2, GrugOverlay.button());
        assertTrue(GrugOverlay.isClick());
        assertFalse(GrugOverlay.isRender());
        assertFalse(
                GrugOverlay.recordItem(new Object(), 0, 0), "A click pass has nowhere to draw.");

        GrugOverlay.take();

        assertTrue(GrugOverlay.closeClick(), "The press was taken.");
        assertFalse(GrugOverlay.isOpen());
    }

    @Test
    void aClickPassThatDidNotTakeThePressSaysSo() {
        GrugOverlay.openClick(426, 240, 125, 37, 176, 166, 300, 20, 0, false);

        assertFalse(GrugOverlay.closeClick());
    }

    @Test
    void aClickPassAnswersWhetherShiftWasHeld() {
        GrugOverlay.openClick(426, 240, 125, 37, 176, 166, 300, 20, 0, true);

        assertTrue(GrugOverlay.shift());

        GrugOverlay.closeClick();

        // The next pass starts with shift clear even when its script does not say.
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);
        assertFalse(GrugOverlay.shift());
        GrugOverlay.closeRender();
    }

    @Test
    void aDrawOutsideAPassIsRefusedAndRecordsNothing() {
        assertFalse(GrugOverlay.recordItem(new Object(), 0, 0));
        assertFalse(GrugOverlay.recordBox(0, 0, 1, 1));
    }

    @Test
    void aBoxTheWrongWayRoundIsRefusedAndRecordsNothing() {
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);

        assertFalse(GrugOverlay.recordBox(10, 0, 5, 18));
        assertFalse(GrugOverlay.recordBox(0, 18, 18, 0));
        assertTrue(GrugOverlay.closeRender().boxes().isEmpty());
    }

    @Test
    void takeDuringARenderPassIsIgnored() {
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);

        GrugOverlay.take();

        // A render pass cannot be taken, so the render closes normally and the take went nowhere.
        assertTrue(GrugOverlay.closeRender().items().isEmpty());
    }

    @Test
    void openingWhileAPassIsOpenBreaksAnInvariant() {
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);

        RuntimeException error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                GrugOverlay.openClick(
                                        426, 240, 125, 37, 176, 166, 300, 20, 0, false));
        assertTrue(error.getMessage().contains("already open"), error.getMessage());
    }

    @Test
    void closingTheWrongKindBreaksAnInvariant() {
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);
        RuntimeException asClick = assertThrows(RuntimeException.class, GrugOverlay::closeClick);
        assertTrue(asClick.getMessage().contains("click pass"), asClick.getMessage());
        GrugOverlay.closeRender();

        GrugOverlay.openClick(426, 240, 125, 37, 176, 166, 300, 20, 0, false);
        RuntimeException asRender = assertThrows(RuntimeException.class, GrugOverlay::closeRender);
        assertTrue(asRender.getMessage().contains("render pass"), asRender.getMessage());
        GrugOverlay.closeClick();
    }

    @Test
    void everyPassGetsItsOwnContextAndClosingForgetsIt() {
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);
        GrugOverlay.Context first = GrugOverlay.context();
        GrugOverlay.closeRender();

        assertNotEquals(null, first);
        assertEquals(null, GrugOverlay.context(), "A closed pass answers no context.");

        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);
        assertNotEquals(first, GrugOverlay.context(), "Each pass is a new context.");
        GrugOverlay.closeRender();
    }

    @Test
    void aPassWithNoExportStillOpensAndCloses() {
        // A handle of zero means the mod has no overlay script, and an invalid export id means the
        // engine has no render to call. Neither reaches the native side, but the pass still closes.
        assertTrue(
                GrugOverlay.render(0, 0, 426, 240, 125, 37, 176, 166, 300, 20).items().isEmpty());
        assertFalse(GrugOverlay.isOpen());

        assertFalse(GrugOverlay.click(0, 0, 426, 240, 125, 37, 176, 166, 300, 20, 0, false));
        assertFalse(GrugOverlay.isOpen());

        assertFalse(GrugOverlay.key(0, 0, 79, true));
        assertFalse(GrugOverlay.isOpen());
    }

    @Test
    void aPassWithAnEntityButNoExportCallsNothing() {
        // The other half of the same guard: the script exists, so the handle is real, but the
        // engine has no such export. The native call has to be skipped either way.
        assertTrue(
                GrugOverlay.render(
                                123,
                                Grug.INVALID_GRUG_EXPORT_FN_ID,
                                426,
                                240,
                                125,
                                37,
                                176,
                                166,
                                300,
                                20)
                        .items()
                        .isEmpty());
        assertFalse(GrugOverlay.isOpen());

        assertFalse(
                GrugOverlay.click(
                        123,
                        Grug.INVALID_GRUG_EXPORT_FN_ID,
                        426,
                        240,
                        125,
                        37,
                        176,
                        166,
                        300,
                        20,
                        0,
                        false));
        assertFalse(GrugOverlay.isOpen());
    }

    @Test
    void aKeyPassWithAnEntityButNoExportCallsNothing() {
        assertFalse(GrugOverlay.key(123, Grug.INVALID_GRUG_EXPORT_FN_ID, 79, true));
        assertFalse(GrugOverlay.isOpen());
    }

    @Test
    void aTakeOutsideAnyPassOrInARenderPassTakesNothing() {
        GrugOverlay.take();
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 300, 20);
        GrugOverlay.take();
        GrugOverlay.closeRender();
        GrugOverlay.openClick(426, 240, 125, 37, 176, 166, 300, 20, 0, false);
        assertFalse(GrugOverlay.closeClick(), "Neither take recorded anything.");
    }
}
